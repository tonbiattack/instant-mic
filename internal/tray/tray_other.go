//go:build !windows

// Package tray shows the receiver's status in the desktop notification area.
//
// The concrete implementation is Windows-only; elsewhere the receiver runs from
// the console as before.
package tray

// Menu is the set of actions a tray icon offers.
type Menu struct {
	PairLabel string
	OnPair    func()
	OnShowQR  func()
	OnCancel  func()
	OnQuit    func()
}

// Run starts the tray loop and blocks until the user quits.
func Run(m Menu) {}

// Quit asks a running tray loop to exit.
func Quit() {}
