package server

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"sync"
	"time"
)

// Pairing requests let the phone ask to pair instead of the user pressing "p".
//
// The security model is unchanged: **approval is always a human action on the
// PC**. A request only asks; it grants nothing. That matters because any device
// on the LAN can send one, so the PC must never auto-approve. What the phone
// gets after approval is a one-time secret bound to its request ID, which it
// then uses with the normal /pair endpoint — the same path a QR scan takes.
//
// Requests are rate limited and short lived so a flood cannot wear the user
// down into approving something they did not mean.

// PairRequest states reported to the phone.
const (
	PairRequestPending  = "pending"
	PairRequestApproved = "approved"
	PairRequestDenied   = "denied"
	PairRequestExpired  = "expired"
)

// ErrPairRequestUnknown reports an unknown or already-consumed request ID.
var ErrPairRequestUnknown = errors.New("unknown pairing request")

// PairRequestTTL bounds how long a request may sit waiting for approval.
const PairRequestTTL = 2 * time.Minute

// maxPendingRequests caps how many requests can wait at once, so a flood cannot
// bury the real one or exhaust memory.
const maxPendingRequests = 4

// PairRequest is one phone's ask to be paired.
type PairRequest struct {
	ID         string
	DeviceID   string
	DeviceName string
	RemoteHost string
	Created    time.Time
	Expires    time.Time
}

type pairRequestState struct {
	request PairRequest
	state   string
	// secret is handed to the approved phone exactly once, then cleared.
	secret string
}

// PairRequestStore holds pending requests.
type PairRequestStore struct {
	mu       sync.Mutex
	requests map[string]*pairRequestState
	order    []string
}

// NewPairRequestStore returns an empty store.
func NewPairRequestStore() *PairRequestStore {
	return &PairRequestStore{requests: map[string]*pairRequestState{}}
}

// ErrTooManyRequests reports that too many requests are already waiting.
var ErrTooManyRequests = errors.New("too many pending pairing requests")

// Add records a new request and returns it. It does not notify anyone; the
// caller decides how to ask the user.
func (s *PairRequestStore) Add(deviceID, deviceName, remoteHost string, now time.Time) (PairRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneLocked(now)

	pending := 0
	for _, st := range s.requests {
		if st.state == PairRequestPending {
			pending++
		}
	}
	if pending >= maxPendingRequests {
		return PairRequest{}, ErrTooManyRequests
	}

	id, err := newRequestID()
	if err != nil {
		return PairRequest{}, err
	}
	req := PairRequest{
		ID:         id,
		DeviceID:   deviceID,
		DeviceName: deviceName,
		RemoteHost: remoteHost,
		Created:    now,
		Expires:    now.Add(PairRequestTTL),
	}
	s.requests[id] = &pairRequestState{request: req, state: PairRequestPending}
	s.order = append(s.order, id)
	return req, nil
}

// State reports the current state and, once approved, hands out the one-time
// secret. The secret is only returned to the holder of the matching request ID
// and is cleared after the first read, so a replayed poll cannot reuse it.
func (s *PairRequestStore) State(id string, now time.Time) (string, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneLocked(now)

	st, ok := s.requests[id]
	if !ok {
		return "", "", ErrPairRequestUnknown
	}
	if st.state == PairRequestApproved && st.secret != "" {
		secret := st.secret
		st.secret = ""
		return PairRequestApproved, secret, nil
	}
	return st.state, "", nil
}

// Approve marks a request approved and attaches the one-time secret. Returns the
// request so the caller can log which device was allowed.
func (s *PairRequestStore) Approve(id string, secret string, now time.Time) (PairRequest, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneLocked(now)

	st, ok := s.requests[id]
	if !ok {
		return PairRequest{}, ErrPairRequestUnknown
	}
	if st.state != PairRequestPending {
		return PairRequest{}, ErrPairRequestUnknown
	}
	st.state = PairRequestApproved
	st.secret = secret
	return st.request, nil
}

// Deny marks a request denied.
func (s *PairRequestStore) Deny(id string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	st, ok := s.requests[id]
	if !ok {
		return ErrPairRequestUnknown
	}
	if st.state == PairRequestPending {
		st.state = PairRequestDenied
	}
	return nil
}

// Cancel drops a pending request (for example when the caller could not ask the
// user, so the phone is not left waiting for nothing).
func (s *PairRequestStore) Cancel(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.requests, id)
}

// Pending returns the requests still waiting for a decision, oldest first.
func (s *PairRequestStore) Pending(now time.Time) []PairRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pruneLocked(now)

	out := make([]PairRequest, 0, len(s.order))
	for _, id := range s.order {
		if st, ok := s.requests[id]; ok && st.state == PairRequestPending {
			out = append(out, st.request)
		}
	}
	return out
}

// pruneLocked expires stale requests and forgets finished ones.
func (s *PairRequestStore) pruneLocked(now time.Time) {
	kept := s.order[:0]
	for _, id := range s.order {
		st, ok := s.requests[id]
		if !ok {
			continue
		}
		if now.After(st.request.Expires) {
			if st.state == PairRequestPending {
				st.state = PairRequestExpired
			}
			// Keep a short tail so the phone learns why it failed, then drop it.
			if now.After(st.request.Expires.Add(PairRequestTTL)) {
				delete(s.requests, id)
				continue
			}
		}
		kept = append(kept, id)
	}
	s.order = kept
}

// matchesSecret reports whether candidate equals want, in constant time.
func matchesSecret(candidate, want string) bool {
	return subtle.ConstantTimeCompare([]byte(candidate), []byte(want)) == 1
}

func newRequestID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
