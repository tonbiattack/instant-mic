// Package server exposes the LAN WebSocket endpoint and pairing flow.
package server

import (
	"context"
	"crypto/tls"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"

	"github.com/tonbiattack/instant-mic/internal/config"
	"github.com/tonbiattack/instant-mic/internal/input"
)

// Config configures the receiver.
type Config struct {
	Store     *config.Store
	Inserter  input.Inserter
	TLS       *tls.Config
	Logger    *slog.Logger
	Pairing   *Pairing
	OnEvent   func(Event)
	OnClients func(int)
}

// Event is an observation for the UI log.
type Event struct {
	Time    time.Time
	Level   string
	Message string
}

// Server receives authenticated messages and injects them.
type Server struct {
	store    *config.Store
	inserter input.Inserter
	tls      *tls.Config
	log      *slog.Logger
	pairing  *Pairing
	onEvent  func(Event)
	onClient func(int)
	dedupe   *dedupe

	mu    sync.Mutex
	conns map[*conn]struct{}

	failMu   sync.Mutex
	failures map[string]failInfo

	httpSrv *http.Server
	addr    atomic.Value // string
}

type failInfo struct {
	count int
	until time.Time
}

// New creates a server.
func New(cfg Config) *Server {
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}
	if cfg.Pairing == nil {
		cfg.Pairing = NewPairing()
	}
	if cfg.Store == nil {
		panic("server: config store is required")
	}
	if cfg.Inserter == nil {
		panic("server: inserter is required")
	}
	return &Server{
		store:    cfg.Store,
		inserter: cfg.Inserter,
		tls:      cfg.TLS,
		log:      logger,
		pairing:  cfg.Pairing,
		onEvent:  cfg.OnEvent,
		onClient: cfg.OnClients,
		dedupe:   newDedupe(10*time.Minute, 1024),
		conns:    map[*conn]struct{}{},
		failures: map[string]failInfo{},
	}
}

func (s *Server) emit(level, msg string) {
	s.log.Log(context.Background(), levelValue(level), msg)
	if s.onEvent != nil {
		s.onEvent(Event{Time: time.Now(), Level: level, Message: msg})
	}
}

func levelValue(level string) slog.Level {
	switch level {
	case "error":
		return slog.LevelError
	case "warn":
		return slog.LevelWarn
	default:
		return slog.LevelInfo
	}
}

// Start listens on addr. When TLS is configured the URL reported to clients is
// wss, otherwise ws (the caller decides how to expose it).
func (s *Server) Start(addr string) error {
	if s.tls != nil {
		return s.startTLS(addr)
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	s.serve(ln)
	return nil
}

func (s *Server) startTLS(addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	s.serve(tls.NewListener(ln, s.tls))
	return nil
}

// StartListener serves on an existing listener (tests).
func (s *Server) StartListener(ln net.Listener) {
	s.serve(ln)
}

func (s *Server) serve(ln net.Listener) {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	mux.HandleFunc("/pair", s.handlePair)
	mux.HandleFunc("/health", s.handleHealth)
	srv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       90 * time.Second,
	}
	s.mu.Lock()
	s.httpSrv = srv
	s.addr.Store(ln.Addr().String())
	s.mu.Unlock()

	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			s.emit("error", "server stopped: "+err.Error())
		}
	}()
}

// Addr is the bound address, e.g. 0.0.0.0:8765.
func (s *Server) Addr() string {
	if v, ok := s.addr.Load().(string); ok {
		return v
	}
	return ""
}

// Port returns the bound TCP port.
func (s *Server) Port() int {
	addr := s.Addr()
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return 0
	}
	var n int
	for _, r := range port {
		if r < '0' || r > '9' {
			return 0
		}
		n = n*10 + int(r-'0')
	}
	return n
}

// Close stops the server and disconnects clients.
func (s *Server) Close() error {
	s.mu.Lock()
	srv := s.httpSrv
	conns := make([]*conn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()

	for _, c := range conns {
		c.close(websocket.CloseGoingAway, "server shutting down")
	}
	if srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		return srv.Shutdown(ctx)
	}
	return nil
}

// ClientCount returns the number of authenticated connections.
func (s *Server) ClientCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}

// BeginPairing opens a pairing window and returns the code.
func (s *Server) BeginPairing(ttl time.Duration) (string, time.Time, error) {
	code, expires, err := s.pairing.Open(ttl)
	if err != nil {
		return "", time.Time{}, err
	}
	s.emit("info", "ペアリングを開始しました（有効期限 "+expires.Format("15:04")+"）")
	return code, expires, nil
}

// CancelPairing closes the pairing window.
func (s *Server) CancelPairing() {
	s.pairing.Close()
	s.emit("info", "ペアリングを中止しました")
}

// Pairing returns the pairing manager.
func (s *Server) Pairing() *Pairing { return s.pairing }

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	// Deliberately terse: the LAN endpoint should not advertise itself.
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

type pairRequest struct {
	Code       string `json:"code"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
}

type pairResponse struct {
	Token       string `json:"token"`
	ServerID    string `json:"serverId"`
	ServerName  string `json:"serverName"`
	CommandPref string `json:"commandPrefix"`
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.allowOrigin(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if !s.allowAttempt(remoteHost(r)) {
		http.Error(w, "too many attempts", http.StatusTooManyRequests)
		return
	}

	var req pairRequest
	if err := decodeJSON(r, &req); err != nil {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.DeviceID == "" || len(req.DeviceID) > 128 {
		http.Error(w, "invalid deviceId", http.StatusBadRequest)
		return
	}
	if err := s.pairing.Verify(req.Code); err != nil {
		s.emit("warn", "ペアリング失敗: "+err.Error())
		status := http.StatusForbidden
		if errors.Is(err, ErrPairingExpired) || errors.Is(err, ErrPairingClosed) {
			status = http.StatusGone
		}
		http.Error(w, err.Error(), status)
		return
	}

	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		name = "Android"
	}
	token, err := s.store.PairDevice(req.DeviceID, name)
	if err != nil {
		http.Error(w, "could not save pairing", http.StatusInternalServerError)
		return
	}
	cfg := s.store.Snapshot()
	s.emit("info", "端末をペアリングしました: "+name)
	writeJSON(w, pairResponse{
		Token:       token,
		ServerID:    cfg.ServerID,
		ServerName:  cfg.ServerName,
		CommandPref: cfg.CommandPrefix,
	})
}

// allowOrigin rejects browser-originated requests. The Android app does not send
// an Origin header, so a web page on the LAN cannot drive the receiver.
func (s *Server) allowOrigin(r *http.Request) bool {
	return r.Header.Get("Origin") == ""
}

func (s *Server) allowAttempt(host string) bool {
	s.failMu.Lock()
	defer s.failMu.Unlock()
	now := time.Now()
	f := s.failures[host]
	if now.Before(f.until) {
		return false
	}
	return true
}

func (s *Server) noteFailure(host string) {
	s.failMu.Lock()
	defer s.failMu.Unlock()
	now := time.Now()
	f := s.failures[host]
	if now.After(f.until) {
		f.count = 0
	}
	f.count++
	if f.count >= 10 {
		f.until = now.Add(2 * time.Minute)
		f.count = 0
	}
	s.failures[host] = f
}

func (s *Server) noteSuccess(host string) {
	s.failMu.Lock()
	defer s.failMu.Unlock()
	delete(s.failures, host)
}

func remoteHost(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
