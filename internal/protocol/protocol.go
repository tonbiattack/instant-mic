// Package protocol defines the messages exchanged between the Android client
// and the Windows receiver.
//
// The wire format is JSON over a WebSocket. Each client operation carries a
// unique ID so the receiver can acknowledge it and refuse to execute a retried
// operation twice.
package protocol

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"unicode/utf8"
)

// Version is the protocol version. Bump it on breaking changes.
const Version = 1

// Message types.
const (
	TypeAuth  = "auth"
	TypeReady = "ready"
	TypeText  = "text"
	TypeKey   = "key"
	TypeErase = "erase"
	TypePing  = "ping"
	TypePong  = "pong"
	TypeAck   = "ack"
	TypeError = "error"
)

// MaxIDLen bounds an operation ID.
const MaxIDLen = 64

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// Envelope is the common shape of every message. Unknown fields are ignored so
// newer clients can add metadata without breaking older receivers.
type Envelope struct {
	Type string `json:"type"`
	ID   string `json:"id,omitempty"`
}

// Auth is the first message on a connection. It carries the per-device token
// issued during pairing, never the pairing code.
type Auth struct {
	Type       string `json:"type"`
	Protocol   int    `json:"protocol"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName,omitempty"`
	Token      string `json:"token"`
	// ClientVersion is informational only.
	ClientVersion string `json:"clientVersion,omitempty"`
}

// Ready confirms authentication.
type Ready struct {
	Type        string `json:"type"`
	Protocol    int    `json:"protocol"`
	ServerName  string `json:"serverName"`
	ServerID    string `json:"serverId"`
	ServerTime  int64  `json:"serverTime"`
	DeviceID    string `json:"deviceId"`
	CommandPref string `json:"commandPrefix"`
}

// Text asks the receiver to type literal Unicode text into the focused window.
type Text struct {
	Type string `json:"type"`
	ID   string `json:"id"`
	Text string `json:"text"`
}

// Key asks the receiver to press a validated key sequence.
type Key struct {
	Type string `json:"type"`
	ID   string `json:"id"`
	Key  string `json:"key"`
	// Count repeats the sequence 1-5 times (optional, defaults to 1).
	Count int `json:"count,omitempty"`
}

// Ping is a keepalive and round-trip measurement request.
type Ping struct {
	Type string `json:"type"`
	ID   string `json:"id"`
	// SentAt is the client clock in milliseconds, echoed back in the pong.
	SentAt int64 `json:"sentAt,omitempty"`
}

// Erase asks the receiver to remove the most recent text insertion.
//
// It is a separate message rather than a burst of Backspace key operations so
// the receiver can verify the count, refuse when nothing was typed, and keep the
// normal key allowlist (with its 5-step repeat cap) untouched.
type Erase struct {
	Type string `json:"type"`
	ID   string `json:"id"`
	// Count is the number of characters to delete, 1..MaxEraseCount.
	Count int `json:"count"`
}

// Pong answers a ping.
type Pong struct {
	Type   string `json:"type"`
	ID     string `json:"id,omitempty"`
	SentAt int64  `json:"sentAt,omitempty"`
	RecvAt int64  `json:"recvAt,omitempty"`
}

// Ack reports the result of a text or key operation. Duplicate means the ID had
// already been executed and was deliberately not executed a second time.
type Ack struct {
	Type      string `json:"type"`
	ID        string `json:"id"`
	OK        bool   `json:"ok"`
	Duplicate bool   `json:"duplicate,omitempty"`
	Error     string `json:"error,omitempty"`
	// ElapsedMS is the receiver-side execution time for latency measurement.
	ElapsedMS int64 `json:"elapsedMs,omitempty"`
	// Applied describes what happened, e.g. "text" or "enter".
	Applied string `json:"applied,omitempty"`
}

// Error is a connection or protocol level error. It may carry the operation ID
// when it relates to one.
type Error struct {
	Type    string `json:"type"`
	ID      string `json:"id,omitempty"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// ValidID reports whether id is an acceptable operation ID.
func ValidID(id string) bool {
	return idPattern.MatchString(id)
}

// DecodeText validates and decodes a text message.
func DecodeText(data []byte) (Text, error) {
	var m Text
	if err := json.Unmarshal(data, &m); err != nil {
		return m, fmt.Errorf("invalid JSON: %w", err)
	}
	if m.Type != TypeText {
		return m, errors.New("not a text message")
	}
	if !ValidID(m.ID) {
		return m, errors.New("missing or invalid id")
	}
	if m.Text == "" {
		return m, errors.New("empty text")
	}
	if !utf8.ValidString(m.Text) {
		return m, errors.New("text is not valid UTF-8")
	}
	return m, nil
}

// DecodeKey validates and decodes a key message. The key name itself is checked
// against the supported set by the caller so this package stays transport-only.
func DecodeKey(data []byte) (Key, error) {
	var m Key
	if err := json.Unmarshal(data, &m); err != nil {
		return m, fmt.Errorf("invalid JSON: %w", err)
	}
	if m.Type != TypeKey {
		return m, errors.New("not a key message")
	}
	if !ValidID(m.ID) {
		return m, errors.New("missing or invalid id")
	}
	if m.Key == "" {
		return m, errors.New("missing key")
	}
	if m.Count < 0 || m.Count > 5 {
		return m, errors.New("count out of range")
	}
	return m, nil
}

// MaxEraseCount bounds a single undo so a malformed message cannot delete an
// arbitrary amount of the user's text. It matches the text length limit.
const MaxEraseCount = 4000

// DecodeErase validates and decodes an erase message.
func DecodeErase(data []byte) (Erase, error) {
	var m Erase
	if err := json.Unmarshal(data, &m); err != nil {
		return m, fmt.Errorf("invalid JSON: %w", err)
	}
	if m.Type != TypeErase {
		return m, errors.New("not an erase message")
	}
	if !ValidID(m.ID) {
		return m, errors.New("missing or invalid id")
	}
	if m.Count < 1 || m.Count > MaxEraseCount {
		return m, errors.New("count out of range")
	}
	return m, nil
}

// DecodeAuth validates and decodes an auth message.
func DecodeAuth(data []byte) (Auth, error) {
	var m Auth
	if err := json.Unmarshal(data, &m); err != nil {
		return m, fmt.Errorf("invalid JSON: %w", err)
	}
	if m.Type != TypeAuth {
		return m, errors.New("first message must be auth")
	}
	if m.Protocol != Version {
		return m, fmt.Errorf("unsupported protocol version %d", m.Protocol)
	}
	if m.DeviceID == "" || len(m.DeviceID) > 128 {
		return m, errors.New("missing or invalid deviceId")
	}
	if m.Token == "" {
		return m, errors.New("missing token")
	}
	return m, nil
}
