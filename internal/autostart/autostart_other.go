//go:build !windows

// Package autostart manages the per-user "run at login" registration.
//
// The implementation is platform specific; this stub keeps non-Windows builds
// working so the receiver code stays portable.
package autostart

// ErrUnsupported reports that the platform has no autostart support.
type UnsupportedError struct{}

func (UnsupportedError) Error() string { return "autostart is not supported on this platform" }

// Enabled reports whether the receiver is registered to start at login.
func Enabled() (bool, error) { return false, nil }

// Set enables or disables startup at login.
func Set(enable bool) error { return UnsupportedError{} }
