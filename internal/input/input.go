// Package input injects text and key events into the focused Windows window.
//
// Injection uses SendInput with KEYEVENTF_UNICODE so Japanese text does not
// depend on the active IME, and never uses the clipboard.
package input

import (
	"errors"

	"github.com/tonbiattack/instant-mic/internal/speech"
)

// ErrNoFocus is returned when no window has the keyboard focus.
var ErrNoFocus = errors.New("no focused window")

// ErrElevatedTarget is returned when the focused window belongs to an elevated
// process, which UIPI prevents us from typing into.
var ErrElevatedTarget = errors.New("focused window belongs to an elevated process")

// Status describes the injection target.
type Status struct {
	HasFocus bool
	Title    string
	Process  string
	Elevated bool
}

// Inserter types text and presses keys.
type Inserter interface {
	// InsertText types text into the currently focused window.
	InsertText(text string) error
	// PressKey performs a fixed key combination.
	PressKey(key speech.Key) error
	// Status inspects the current foreground window.
	Status() Status
}
