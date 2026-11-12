// Package server exposes the LAN WebSocket endpoint, pairing endpoint and
// connection lifecycle for the Windows receiver.
package server

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"sync"
	"time"
)

// Pairing errors surfaced to the phone.
var (
	ErrPairingClosed  = errors.New("pairing is not open")
	ErrPairingExpired = errors.New("pairing code expired")
	ErrPairingCode    = errors.New("pairing code did not match")
	ErrPairingLocked  = errors.New("too many attempts; wait a minute")
)

// Pairing holds the short-lived code shown on the PC.
//
// Pairing is only open while the user has started it, so an attacker on the LAN
// cannot brute force a code that is never active. Opening pairing issues a fresh
// six-digit code with an expiry; a successful pairing closes it.
type Pairing struct {
	mu       sync.Mutex
	code     string
	expires  time.Time
	ttl      time.Duration
	attempts int
	lockout  time.Time
}

// NewPairing returns a closed pairing manager.
func NewPairing() *Pairing { return &Pairing{} }

// Open starts pairing and returns the new code and its expiry.
func (p *Pairing) Open(ttl time.Duration) (string, time.Time, error) {
	if ttl <= 0 {
		ttl = 10 * time.Minute
	}
	code, err := randomCode()
	if err != nil {
		return "", time.Time{}, err
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.code = code
	p.ttl = ttl
	p.expires = time.Now().Add(ttl)
	p.attempts = 0
	p.lockout = time.Time{}
	return code, p.expires, nil
}

// Close disables pairing and forgets the code.
func (p *Pairing) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.code = ""
	p.expires = time.Time{}
}

// Active reports whether pairing is currently open.
func (p *Pairing) Active() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.code != "" && time.Now().Before(p.expires)
}

// State describes the current pairing window for the UI.
type State struct {
	Open    bool
	Expires time.Time
}

// State returns the current pairing state.
func (p *Pairing) State() State {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.code == "" || time.Now().After(p.expires) {
		return State{}
	}
	return State{Open: true, Expires: p.expires}
}

// Verify checks a candidate code and consumes the pairing window on success.
func (p *Pairing) Verify(candidate string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	now := time.Now()
	if p.code == "" {
		return ErrPairingClosed
	}
	if !p.lockout.IsZero() {
		if now.Before(p.lockout) {
			return ErrPairingLocked
		}
		p.lockout = time.Time{}
		p.attempts = 0
	}
	if now.After(p.expires) {
		p.code = ""
		return ErrPairingExpired
	}

	candidate = strings.TrimSpace(candidate)
	if subtle.ConstantTimeCompare([]byte(candidate), []byte(p.code)) != 1 {
		p.attempts++
		if p.attempts >= 5 {
			p.lockout = now.Add(time.Minute)
			p.attempts = 0
		}
		return ErrPairingCode
	}

	// A code is single use.
	p.code = ""
	p.expires = time.Time{}
	return nil
}

func randomCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}
