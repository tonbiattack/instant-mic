package server

import (
	"sync"
	"testing"
	"time"
)

// The pairing-request flow is the one place a device on the LAN can make the PC
// show something. These tests pin the properties that keep it safe.

func TestPairRequestStartsPending(t *testing.T) {
	store := NewPairRequestStore()
	now := time.Now()

	req, err := store.Add("dev-1", "Pixel", "192.168.1.10", now)
	if err != nil {
		t.Fatal(err)
	}
	if req.ID == "" {
		t.Fatal("no request id")
	}

	state, secret, err := store.State(req.ID, now)
	if err != nil {
		t.Fatal(err)
	}
	if state != PairRequestPending {
		t.Fatalf("state = %q, want pending", state)
	}
	// A pending request must never hand out a secret: the phone has not been
	// approved yet.
	if secret != "" {
		t.Fatalf("pending request leaked a secret: %q", secret)
	}
}

func TestPairRequestSecretIsHandedOutOnce(t *testing.T) {
	store := NewPairRequestStore()
	now := time.Now()
	req, _ := store.Add("dev-1", "Pixel", "192.168.1.10", now)

	if _, err := store.Approve(req.ID, "secret-value", now); err != nil {
		t.Fatal(err)
	}

	state, secret, err := store.State(req.ID, now)
	if err != nil {
		t.Fatal(err)
	}
	if state != PairRequestApproved || secret != "secret-value" {
		t.Fatalf("first poll = %q, %q", state, secret)
	}

	// A second poll must not return the secret again: someone polling in a loop
	// could otherwise replay it.
	state, secret, err = store.State(req.ID, now)
	if err != nil {
		t.Fatal(err)
	}
	if secret != "" {
		t.Fatalf("secret handed out twice: %q", secret)
	}
	if state != PairRequestApproved {
		t.Fatalf("state after consumption = %q", state)
	}
}

func TestPairRequestDeniedNeverGetsSecret(t *testing.T) {
	store := NewPairRequestStore()
	now := time.Now()
	req, _ := store.Add("dev-1", "Pixel", "192.168.1.10", now)

	if err := store.Deny(req.ID, now); err != nil {
		t.Fatal(err)
	}
	state, secret, err := store.State(req.ID, now)
	if err != nil {
		t.Fatal(err)
	}
	if state != PairRequestDenied {
		t.Fatalf("state = %q", state)
	}
	if secret != "" {
		t.Fatalf("denied request returned a secret: %q", secret)
	}

	// Approving after a denial must fail: the user already said no.
	if _, err := store.Approve(req.ID, "late", now); err == nil {
		t.Fatal("approve after deny succeeded")
	}
}

func TestPairRequestExpires(t *testing.T) {
	store := NewPairRequestStore()
	now := time.Now()
	req, _ := store.Add("dev-1", "Pixel", "192.168.1.10", now)

	later := now.Add(PairRequestTTL + time.Second)
	state, secret, err := store.State(req.ID, later)
	if err != nil {
		t.Fatal(err)
	}
	if state != PairRequestExpired {
		t.Fatalf("state = %q, want expired", state)
	}
	if secret != "" {
		t.Fatalf("expired request returned a secret")
	}
	// Approving an expired request must fail.
	if _, err := store.Approve(req.ID, "x", later); err == nil {
		t.Fatal("approve after expiry succeeded")
	}
}

func TestPairRequestIsRateLimited(t *testing.T) {
	store := NewPairRequestStore()
	now := time.Now()

	for i := 0; i < maxPendingRequests; i++ {
		if _, err := store.Add("dev", "Pixel", "192.168.1.10", now); err != nil {
			t.Fatalf("request %d: %v", i, err)
		}
	}
	if _, err := store.Add("dev", "Pixel", "192.168.1.10", now); err == nil {
		t.Fatal("a flood of requests was accepted")
	}
}

func TestPairRequestUnknownID(t *testing.T) {
	store := NewPairRequestStore()
	if _, _, err := store.State("nope", time.Now()); err == nil {
		t.Fatal("unknown id returned a state")
	}
	if _, err := store.Approve("nope", "x", time.Now()); err == nil {
		t.Fatal("approving an unknown id succeeded")
	}
}

func TestPairRequestConcurrentApproveIsSafe(t *testing.T) {
	store := NewPairRequestStore()
	now := time.Now()
	req, _ := store.Add("dev-1", "Pixel", "192.168.1.10", now)

	// Two approvals racing must not both win: only one secret may be attached.
	var wg sync.WaitGroup
	var approved int
	var mu sync.Mutex
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := store.Approve(req.ID, "s", now); err == nil {
				mu.Lock()
				approved++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if approved != 1 {
		t.Fatalf("approved %d times, want exactly 1", approved)
	}
}

func TestMatchesSecret(t *testing.T) {
	if !matchesSecret("abc", "abc") {
		t.Fatal("equal secrets did not match")
	}
	if matchesSecret("abc", "abd") {
		t.Fatal("different secrets matched")
	}
	if matchesSecret("", "abc") {
		t.Fatal("empty candidate matched")
	}
}
