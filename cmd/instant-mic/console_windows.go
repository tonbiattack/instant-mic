//go:build windows

package main

import (
	"golang.org/x/sys/windows"
)

// enableUTF8Console switches the console to UTF-8 so the QR block characters
// render instead of "?". Windows Terminal already defaults to UTF-8, but the
// legacy console host does not.
func enableUTF8Console() {
	const utf8CP = 65001
	_ = windows.SetConsoleOutputCP(utf8CP)
	_ = windows.SetConsoleCP(utf8CP)
}
