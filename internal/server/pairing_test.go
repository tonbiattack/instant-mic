package server

import (
	"sync"
	"testing"
	"time"
)

func TestDedupeRemembersResults(t *testing.T) {
	d := newDedupe(time.Minute, 4)
	if _, ok := d.Lookup("a"); ok {
		t.Fatal("empty dedupe returned a hit")
	}
	d.Store("a", AckResult{OK: true, Applied: "text"})
	res, ok := d.Lookup("a")
	if !ok || !res.OK || !res.Duplicate || res.Applied != "text" {
		t.Fatalf("Lookup = %+v, %v", res, ok)
	}
}

func TestDedupeExpires(t *testing.T) {
	d := newDedupe(10*time.Millisecond, 4)
	d.Store("a", AckResult{OK: true})
	time.Sleep(30 * time.Millisecond)
	if _, ok := d.Lookup("a"); ok {
		t.Fatal("expired entry still present")
	}
}

func TestDedupeBounds(t *testing.T) {
	d := newDedupe(time.Minute, 2)
	d.Store("a", AckResult{})
	d.Store("b", AckResult{})
	d.Store("c", AckResult{})
	if _, ok := d.Lookup("a"); ok {
		t.Fatal("oldest entry not evicted")
	}
	if _, ok := d.Lookup("c"); !ok {
		t.Fatal("newest entry missing")
	}
}

func TestDedupeConcurrent(t *testing.T) {
	d := newDedupe(time.Minute, 1000)
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				d.Store("id", AckResult{OK: true})
				d.Lookup("id")
			}
		}()
	}
	wg.Wait()
}

func TestPairingOpenAndVerify(t *testing.T) {
	p := NewPairing()
	if p.Active() {
		t.Fatal("pairing open by default")
	}
	code, _, err := p.Open(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 6 {
		t.Fatalf("code = %q", code)
	}
	if !p.Active() {
		t.Fatal("pairing not active after Open")
	}
	if err := p.Verify("000000" + code); err == nil {
		t.Fatal("wrong code accepted")
	}
	if err := p.Verify(code); err != nil {
		t.Fatalf("correct code rejected: %v", err)
	}
	// Single use.
	if err := p.Verify(code); err != ErrPairingClosed {
		t.Fatalf("reused code err = %v", err)
	}
}

func TestPairingExpires(t *testing.T) {
	p := NewPairing()
	code, _, _ := p.Open(10 * time.Millisecond)
	time.Sleep(30 * time.Millisecond)
	if p.Active() {
		t.Fatal("expired pairing still active")
	}
	if err := p.Verify(code); err != ErrPairingExpired {
		t.Fatalf("err = %v, want expired", err)
	}
}

func TestPairingLockout(t *testing.T) {
	p := NewPairing()
	_, _, _ = p.Open(time.Minute)
	for i := 0; i < 5; i++ {
		_ = p.Verify("999999")
	}
	if err := p.Verify("999999"); err != ErrPairingLocked {
		t.Fatalf("err = %v, want locked", err)
	}
}

func TestPairingClose(t *testing.T) {
	p := NewPairing()
	_, _, _ = p.Open(time.Minute)
	p.Close()
	if p.Active() {
		t.Fatal("pairing active after Close")
	}
}
