package server

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// Phone-initiated pairing.
//
// The flow is deliberately two-sided:
//
//	POST /pair/request          phone asks; nothing is granted
//	GET  /pair/request/{id}     phone polls until the user decides
//	                            -> {"state":"approved","secret":"…"} exactly once
//	POST /pair                  normal pairing with that secret
//
// Approval always happens on the PC through Config.OnPairRequest. Nothing in
// this file approves automatically, because any device on the LAN can reach
// these endpoints.

const pairRequestPrefix = "/pair/request"

type pairRequestBody struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
}

type pairRequestCreated struct {
	ID      string `json:"id"`
	State   string `json:"state"`
	Expires int64  `json:"expires"`
}

type pairRequestStatus struct {
	State  string `json:"state"`
	Secret string `json:"secret,omitempty"`
	Error  string `json:"error,omitempty"`
}

func (s *Server) handlePairRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.allowOrigin(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	host := remoteHost(r)
	if !s.allowAttempt(host) {
		http.Error(w, "too many attempts", http.StatusTooManyRequests)
		return
	}
	// Without a way to ask the user there is no safe answer, so refuse rather
	// than leave the phone waiting for a decision that can never come.
	if s.onPairRequest == nil {
		http.Error(w, "pairing requests are disabled", http.StatusNotImplemented)
		return
	}

	var req pairRequestBody
	if err := decodeJSON(r, &req); err != nil {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if err := validPairRequestDevice(req.DeviceID); err != nil {
		http.Error(w, "invalid deviceId", http.StatusBadRequest)
		return
	}
	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		name = "Android"
	}

	created, err := s.requests.Add(req.DeviceID, name, host, time.Now())
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, ErrTooManyRequests) {
			status = http.StatusTooManyRequests
		}
		http.Error(w, err.Error(), status)
		return
	}
	s.emit("info", "ペアリング要求を受け付けました: "+name+" ("+host+")")

	// Ask the user on a goroutine: the HTTP response must return immediately so
	// the phone can start polling, and the user may take a while to answer.
	go s.awaitPairDecision(created)

	writeJSON(w, pairRequestCreated{
		ID:      created.ID,
		State:   PairRequestPending,
		Expires: created.Expires.UnixMilli(),
	})
}

// awaitPairDecision blocks on the user's answer, then records it.
func (s *Server) awaitPairDecision(req PairRequest) {
	approve := false
	func() {
		// A panic in the prompt must not leave the request pending forever.
		defer func() { _ = recover() }()
		approve = s.onPairRequest(req)
	}()

	if !approve {
		_ = s.requests.Deny(req.ID, time.Now())
		s.emit("info", "ペアリング要求を拒否しました: "+req.DeviceName)
		return
	}

	// Reuse the pairing manager: the approved phone receives a normal one-time
	// secret, so /pair needs no special case and the secret is single use by the
	// same code path a QR scan uses.
	secret, ok := s.newPairingSecret()
	if !ok {
		_ = s.requests.Deny(req.ID, time.Now())
		return
	}
	if _, err := s.requests.Approve(req.ID, secret, time.Now()); err != nil {
		s.emit("warn", "ペアリング要求の承認に失敗: "+err.Error())
		return
	}
	s.emit("info", "ペアリング要求を承認しました: "+req.DeviceName)
}

// newPairingSecret issues a one-time secret bound to the current pairing window.
func (s *Server) newPairingSecret() (string, bool) {
	if !s.pairing.Active() {
		// Approval implies the window should be open; open a short one.
		if _, _, _, err := s.pairing.OpenWithSecret(PairRequestTTL + time.Minute); err != nil {
			return "", false
		}
	}
	secret := s.pairing.Secret()
	return secret, secret != ""
}

func (s *Server) handlePairRequestStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.allowOrigin(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, pairRequestPrefix+"/")
	if id == "" || strings.Contains(id, "/") {
		http.Error(w, "missing request id", http.StatusBadRequest)
		return
	}

	state, secret, err := s.requests.State(id, time.Now())
	if err != nil {
		http.Error(w, "unknown request", http.StatusNotFound)
		return
	}
	writeJSON(w, pairRequestStatus{State: state, Secret: secret})
}

func validPairRequestDevice(id string) error {
	if id == "" {
		return errors.New("missing deviceId")
	}
	if len(id) > 128 {
		return errors.New("deviceId too long")
	}
	for _, r := range id {
		// Keep IDs to URL-safe characters: the request is logged and the phone
		// uses a separate opaque request ID, so this is only about hygiene.
		if r < 0x20 || r == 0x7f {
			return errors.New("deviceId has control characters")
		}
	}
	return nil
}

// pairRequestJSON is used by tests to decode responses.
func pairRequestJSON(data []byte) (pairRequestStatus, error) {
	var out pairRequestStatus
	err := json.Unmarshal(data, &out)
	return out, err
}
