package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/tonbiattack/instant-mic/internal/input"
	"github.com/tonbiattack/instant-mic/internal/protocol"
	"github.com/tonbiattack/instant-mic/internal/speech"
)

const (
	writeWait      = 5 * time.Second
	pongWait       = 75 * time.Second
	pingPeriod     = 25 * time.Second
	maxMessageSize = 64 << 10
	authWait       = 10 * time.Second
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// Only native clients are allowed; a browser page must not connect even
	// before authentication. The Android app sends no Origin header.
	CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" },
}

// pingLoop keeps the connection alive and lets the client measure latency.
func (c *conn) pingLoop() {
	ticker := time.NewTicker(pingPeriod)
	defer ticker.Stop()
	for range ticker.C {
		c.writeMu.Lock()
		if c.closed {
			c.writeMu.Unlock()
			return
		}
		_ = c.ws.SetWriteDeadline(time.Now().Add(writeWait))
		err := c.ws.WriteMessage(websocket.PingMessage, nil)
		c.writeMu.Unlock()
		if err != nil {
			c.close(websocket.CloseGoingAway, "")
			return
		}
	}
}

type conn struct {
	srv    *Server
	ws     *websocket.Conn
	device string
	name   string

	writeMu sync.Mutex
	closed  bool

	// undo tracks this phone's last text insertion for "言い直し".
	undo *undo
}

func jsonUnmarshal(data []byte, v any) error { return json.Unmarshal(data, v) }

func (c *conn) send(v any) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if c.closed {
		return errors.New("connection closed")
	}
	_ = c.ws.SetWriteDeadline(time.Now().Add(writeWait))
	return c.ws.WriteJSON(v)
}

func (c *conn) close(code int, reason string) {
	c.writeMu.Lock()
	if c.closed {
		c.writeMu.Unlock()
		return
	}
	c.closed = true
	c.writeMu.Unlock()

	_ = c.ws.WriteControl(
		websocket.CloseMessage,
		websocket.FormatCloseMessage(code, reason),
		time.Now().Add(time.Second),
	)
	_ = c.ws.Close()
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	if !s.allowOrigin(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	host := remoteHost(r)
	if !s.allowAttempt(host) {
		http.Error(w, "too many attempts", http.StatusTooManyRequests)
		return
	}

	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(maxMessageSize)
	_ = ws.SetReadDeadline(time.Now().Add(authWait))

	c := &conn{srv: s, ws: ws, undo: newUndo()}
	ready, err := c.authenticate(s)
	if err != nil {
		s.noteFailure(host)
		s.emit("warn", "認証を拒否しました: "+err.Error())
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "auth_failed", Message: err.Error()})
		c.close(websocket.ClosePolicyViolation, "authentication failed")
		return
	}
	s.noteSuccess(host)

	s.mu.Lock()
	s.conns[c] = struct{}{}
	count := len(s.conns)
	s.mu.Unlock()
	if s.onClient != nil {
		s.onClient(count)
	}
	s.emit("info", "接続しました: "+c.name)

	if err := c.send(ready); err != nil {
		s.dropConn(c)
		return
	}
	go c.pingLoop()
	c.readLoop()
}

func (c *conn) authenticate(s *Server) (protocol.Ready, error) {
	var zero protocol.Ready

	_, data, err := c.ws.ReadMessage()
	if err != nil {
		return zero, fmt.Errorf("read auth: %w", err)
	}
	auth, err := protocol.DecodeAuth(data)
	if err != nil {
		return zero, err
	}
	dev, ok := s.store.Authenticate(auth.DeviceID, auth.Token)
	if !ok {
		return zero, errors.New("unknown device or invalid token")
	}
	c.device = dev.ID
	c.name = dev.Name
	if c.name == "" {
		c.name = dev.ID
	}

	// After authentication, keepalives govern the read deadline.
	_ = c.ws.SetReadDeadline(time.Now().Add(pongWait))
	c.ws.SetPongHandler(func(string) error {
		return c.ws.SetReadDeadline(time.Now().Add(pongWait))
	})
	c.ws.SetPingHandler(func(data string) error {
		err := c.ws.WriteControl(websocket.PongMessage, []byte(data), time.Now().Add(writeWait))
		_ = c.ws.SetReadDeadline(time.Now().Add(pongWait))
		return err
	})

	s.store.MarkSeen(dev.ID)
	cfg := s.store.Snapshot()
	return protocol.Ready{
		Type:        protocol.TypeReady,
		Protocol:    protocol.Version,
		ServerName:  cfg.ServerName,
		ServerID:    cfg.ServerID,
		ServerTime:  time.Now().UnixMilli(),
		DeviceID:    dev.ID,
		CommandPref: cfg.CommandPrefix,
	}, nil
}

func (c *conn) readLoop() {
	defer c.srv.dropConn(c)

	for {
		_, data, err := c.ws.ReadMessage()
		if err != nil {
			return
		}
		_ = c.ws.SetReadDeadline(time.Now().Add(pongWait))
		c.handleMessage(data)
	}
}

func (c *conn) handleMessage(data []byte) {
	var env protocol.Envelope
	if err := jsonUnmarshal(data, &env); err != nil {
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "bad_message", Message: "invalid JSON"})
		return
	}

	switch env.Type {
	case protocol.TypeText:
		c.handleText(data)
	case protocol.TypeKey:
		c.handleKey(data)
	case protocol.TypeErase:
		c.handleErase(data)
	case protocol.TypePing:
		c.handlePing(data)
	case "":
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "bad_message", Message: "missing type"})
	default:
		_ = c.send(protocol.Error{
			Type:    protocol.TypeError,
			ID:      env.ID,
			Code:    "unsupported",
			Message: "unsupported message type",
		})
	}
}

// handleText applies the receiver-side command rules before typing anything.
// A finalized transcript that is a command becomes a key action, so text that
// looks like a command can never be typed into the target application.
func (c *conn) handleText(data []byte) {
	msg, err := protocol.DecodeText(data)
	if err != nil {
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "bad_text", Message: err.Error()})
		return
	}
	if err := validateText(msg.Text); err != nil {
		c.reply(protocol.Ack{Type: protocol.TypeAck, ID: msg.ID, OK: false, Error: err.Error()})
		return
	}

	if res, ok := c.srv.dedupe.Lookup(msg.ID); ok {
		c.replyFrom(msg.ID, res, 0)
		return
	}

	result := speech.Interpret(msg.Text)
	start := time.Now()
	var res AckResult
	switch {
	case msg.Literal:
		// The client says this is a settled phrase of a longer utterance, not a
		// complete one, so it must never be treated as a command. A phrase that
		// starts with the wake prefix is refused outright: the phone kept
		// incremental input off for commands, so this means the two sides
		// disagree and typing it could duplicate the final result.
		if err := validateLiteral(msg.Text); err != nil {
			res = AckResult{OK: false, Error: err.Error()}
		} else if err := c.srv.inserter.InsertText(msg.Text); err != nil {
			res = AckResult{OK: false, Error: humanInputError(err)}
		} else {
			res = AckResult{OK: true, Applied: "text"}
		}
	case result.Kind == speech.KindErase:
		// A spoken "言い直し" arrives as text, so it uses the same undo record
		// as an explicit erase message.
		res = c.applyErase()
	default:
		res = c.srv.apply(result)
	}
	elapsed := time.Since(start).Milliseconds()

	// A successful literal insertion is the only thing "言い直し" may remove.
	// Anything else changes the target in ways the receiver cannot measure.
	if res.OK && (msg.Literal || result.Kind == speech.KindText) {
		// An incremental phrase continues the same insertion, so its length
		// accumulates until a key action or an undo closes the record.
		if msg.Literal {
			c.undo.addText(len([]rune(msg.Text)))
		} else {
			c.undo.recordText(len([]rune(result.Text)))
		}
	} else if res.OK {
		c.undo.invalidate()
	}

	c.srv.dedupe.Store(msg.ID, res)
	if !res.OK {
		c.srv.emit("warn", "入力に失敗: "+res.Error)
	}
	c.replyFrom(msg.ID, res, elapsed)
}

// applyErase removes the last text insertion, if one is pending.
func (c *conn) applyErase() AckResult {
	pending, ok := c.undo.take()
	if !ok {
		return AckResult{OK: false, Error: "取り消せる入力がありません"}
	}
	if err := c.srv.inserter.EraseText(pending); err != nil {
		// The text is still there, so keep the record for a retry.
		c.undo.recordText(pending)
		return AckResult{OK: false, Error: humanInputError(err)}
	}
	return AckResult{OK: true, Applied: "erase"}
}

// handleErase removes the most recent text insertion.
//
// The phone sends the count it believes was typed, but the receiver decides:
// a mismatch, a missing record or a count beyond the limit is refused rather
// than deleting an unknown amount of the user's document.
func (c *conn) handleErase(data []byte) {
	msg, err := protocol.DecodeErase(data)
	if err != nil {
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "bad_erase", Message: err.Error()})
		return
	}
	if res, ok := c.srv.dedupe.Lookup(msg.ID); ok {
		c.replyFrom(msg.ID, res, 0)
		return
	}

	pending, ok := c.undo.take()
	if !ok {
		res := AckResult{OK: false, Error: "取り消せる入力がありません"}
		c.srv.dedupe.Store(msg.ID, res)
		c.replyFrom(msg.ID, res, 0)
		return
	}
	if msg.Count != pending {
		// Put the record back: the phone asked to delete a different amount, so
		// nothing is deleted and a corrected request can still match.
		c.undo.recordText(pending)
		res := AckResult{OK: false, Error: "直前の入力と一致しないため取り消しません"}
		c.srv.dedupe.Store(msg.ID, res)
		c.replyFrom(msg.ID, res, 0)
		return
	}

	start := time.Now()
	err = c.srv.inserter.EraseText(pending)
	elapsed := time.Since(start).Milliseconds()
	res := AckResult{OK: err == nil, Applied: "erase"}
	if err != nil {
		// The text is still there, so keep the record for a retry.
		c.undo.recordText(pending)
		res.Error = humanInputError(err)
		c.srv.emit("warn", "取り消しに失敗: "+res.Error)
	}
	c.srv.dedupe.Store(msg.ID, res)
	c.replyFrom(msg.ID, res, elapsed)
}

func (c *conn) handleKey(data []byte) {
	msg, err := protocol.DecodeKey(data)
	if err != nil {
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "bad_key", Message: err.Error()})
		return
	}
	seq, err := speech.ParseSequence(msg.Key)
	if err != nil {
		_ = c.send(protocol.Error{Type: protocol.TypeError, ID: msg.ID, Code: "bad_key", Message: "unsupported key"})
		return
	}
	if res, ok := c.srv.dedupe.Lookup(msg.ID); ok {
		c.replyFrom(msg.ID, res, 0)
		return
	}

	count := msg.Count
	if count < 1 {
		count = 1
	}
	start := time.Now()
	err = c.srv.inserter.PressKey(seq, count)
	// A key action moves the caret or edits text in an app-specific way, so the
	// previous insertion can no longer be undone safely.
	c.undo.invalidate()
	res := AckResult{OK: err == nil, Applied: seq.String()}
	if err != nil {
		res.Error = humanInputError(err)
		c.srv.emit("warn", "キー操作に失敗: "+res.Error)
	}
	elapsed := time.Since(start).Milliseconds()
	c.srv.dedupe.Store(msg.ID, res)
	c.replyFrom(msg.ID, res, elapsed)
}

func (c *conn) handlePing(data []byte) {
	var msg protocol.Ping
	if err := jsonUnmarshal(data, &msg); err != nil {
		_ = c.send(protocol.Error{Type: protocol.TypeError, Code: "bad_message", Message: "invalid ping"})
		return
	}
	_ = c.send(protocol.Pong{
		Type:   protocol.TypePong,
		ID:     msg.ID,
		SentAt: msg.SentAt,
		RecvAt: time.Now().UnixMilli(),
	})
}

func (c *conn) reply(ack protocol.Ack) {
	_ = c.send(ack)
}

func (c *conn) replyFrom(id string, res AckResult, elapsed int64) {
	_ = c.send(protocol.Ack{
		Type:      protocol.TypeAck,
		ID:        id,
		OK:        res.OK,
		Duplicate: res.Duplicate,
		Error:     res.Error,
		Applied:   res.Applied,
		ElapsedMS: elapsed,
	})
}

// apply executes an interpreted transcript.
func (s *Server) apply(result speech.Result) AckResult {
	switch result.Kind {
	case speech.KindEmpty:
		return AckResult{OK: false, Error: "空の音声結果です"}
	case speech.KindUnknownCommand:
		return AckResult{OK: false, Error: "未対応のコマンドです: " + result.Raw}
	case speech.KindErase:
		return AckResult{OK: false, Error: "取り消す入力がありません"}
	case speech.KindKey:
		if err := s.inserter.PressKey(result.Key, result.Count); err != nil {
			return AckResult{OK: false, Error: humanInputError(err)}
		}
		return AckResult{OK: true, Applied: result.Key.String()}
	default:
		if err := s.inserter.InsertText(result.Text); err != nil {
			return AckResult{OK: false, Error: humanInputError(err)}
		}
		return AckResult{OK: true, Applied: "text"}
	}
}

func (s *Server) dropConn(c *conn) {
	c.close(websocket.CloseNormalClosure, "")

	s.mu.Lock()
	if _, ok := s.conns[c]; !ok {
		s.mu.Unlock()
		return
	}
	delete(s.conns, c)
	count := len(s.conns)
	s.mu.Unlock()

	if s.onClient != nil {
		s.onClient(count)
	}
	s.emit("info", "切断しました: "+c.name)
}

// humanInputError turns injection errors into messages the phone can show.
func humanInputError(err error) string {
	switch {
	case errors.Is(err, input.ErrNoFocus):
		return "入力先のウィンドウがありません"
	case errors.Is(err, input.ErrElevatedTarget):
		return "管理者権限のウィンドウには入力できません"
	default:
		return err.Error()
	}
}
