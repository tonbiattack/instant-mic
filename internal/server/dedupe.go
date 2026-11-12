package server

import (
	"sync"
	"time"
)

// dedupe remembers recently executed operation IDs so a client retry after a
// lost ACK cannot type or press twice.
type dedupe struct {
	mu      sync.Mutex
	ttl     time.Duration
	entries map[string]dedupeEntry
	order   []string
	max     int
}

type dedupeEntry struct {
	ack     AckResult
	expires time.Time
}

// AckResult is the cached outcome of an operation.
type AckResult struct {
	OK        bool
	Applied   string
	Error     string
	ElapsedMS int64
	Duplicate bool
}

func newDedupe(ttl time.Duration, max int) *dedupe {
	if ttl <= 0 {
		ttl = 10 * time.Minute
	}
	if max <= 0 {
		max = 512
	}
	return &dedupe{ttl: ttl, entries: map[string]dedupeEntry{}, max: max}
}

// Lookup returns a cached result when the ID was already executed.
func (d *dedupe) Lookup(id string) (AckResult, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	e, ok := d.entries[id]
	if !ok {
		return AckResult{}, false
	}
	if time.Now().After(e.expires) {
		delete(d.entries, id)
		return AckResult{}, false
	}
	res := e.ack
	res.Duplicate = true
	return res, true
}

// Store records the result of an executed operation.
func (d *dedupe) Store(id string, res AckResult) {
	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now()
	if _, exists := d.entries[id]; !exists {
		d.order = append(d.order, id)
	}
	d.entries[id] = dedupeEntry{ack: res, expires: now.Add(d.ttl)}
	d.pruneLocked(now)
}

func (d *dedupe) pruneLocked(now time.Time) {
	kept := d.order[:0]
	for _, id := range d.order {
		e, ok := d.entries[id]
		if !ok {
			continue
		}
		if now.After(e.expires) {
			delete(d.entries, id)
			continue
		}
		kept = append(kept, id)
	}
	d.order = kept

	for len(d.entries) > d.max && len(d.order) > 0 {
		delete(d.entries, d.order[0])
		d.order = d.order[1:]
	}
}
