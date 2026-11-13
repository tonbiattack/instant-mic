package server

import "sync"

// undo remembers what the receiver last typed so a spoken "言い直し" can remove
// exactly that text.
//
// Only literal text insertions are tracked. Key actions (Enter, Backspace, …)
// change the target in ways the receiver cannot measure, so they clear the undo
// record: a later undo must not pretend to know the state of the document. The
// record is per connection because it describes that phone's last action.
type undo struct {
	mu    sync.Mutex
	count int
}

// maxUndoCount matches the text length limit, so accumulated incremental
// phrases can never ask for an unbounded erase.
const maxUndoCount = 4000

func newUndo() *undo { return &undo{} }

// recordText remembers that count characters were just typed.
func (u *undo) recordText(count int) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if count < 0 {
		count = 0
	}
	u.count = count
}

// addText extends the record by count characters, so an utterance typed as
// several incremental phrases can still be undone as one insertion.
func (u *undo) addText(count int) {
	if count <= 0 {
		return
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	u.count += count
	if u.count > maxUndoCount {
		u.count = maxUndoCount
	}
}

// invalidate forgets the record after an action the receiver cannot measure.
func (u *undo) invalidate() {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.count = 0
}

// take returns the pending count and clears it, so the same insertion can never
// be undone twice (for example by a retried message).
func (u *undo) take() (int, bool) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.count <= 0 {
		return 0, false
	}
	count := u.count
	u.count = 0
	return count, true
}

// peek reports the pending count without consuming it, for diagnostics.
func (u *undo) peek() int {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.count
}
