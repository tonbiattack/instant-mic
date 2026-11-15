//go:build windows

package main

import (
	"runtime"
	"syscall"
	"unsafe"
)

// askPairRequestDialog shows the pairing request as a native message box.
//
// Tray mode has no console, so the request has to surface some other way. A
// message box is chosen over a tray balloon because balloons can be missed and
// this decision grants access; the box stays until the user answers.
//
// The default button is "いいえ" and closing the box denies, so an accidental
// Enter or Escape never grants pairing.
func askPairRequestDialog(deviceName, remoteHost string) bool {
	type result struct{ approved bool }
	done := make(chan result, 1)

	go func() {
		// MessageBox must run on a locked OS thread with a message pump owned by
		// the caller's thread; spawning it here keeps the HTTP handler free.
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		done <- result{approved: messageBoxYesNo(deviceName, remoteHost)}
	}()

	return (<-done).approved
}

const (
	mbYesNo          = 0x00000004
	mbIconWarning    = 0x00000030
	mbDefaultButton2 = 0x00000100
	mbSetForeground  = 0x00010000
	idYes            = 6
)

var (
	user32          = syscall.NewLazyDLL("user32.dll")
	procMessageBoxW = user32.NewProc("MessageBoxW")
)

// messageBoxYesNo returns true only for an explicit Yes.
func messageBoxYesNo(deviceName, remoteHost string) bool {
	text := "端末: " + deviceName + "\r\n" +
		"から: " + remoteHost + "\r\n\r\n" +
		"この端末をペアリングしますか？"
	caption := "instant-mic — ペアリング要求"

	textPtr, err := syscall.UTF16PtrFromString(text)
	if err != nil {
		return false
	}
	captionPtr, err := syscall.UTF16PtrFromString(caption)
	if err != nil {
		return false
	}

	ret, _, _ := procMessageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(textPtr)),
		uintptr(unsafe.Pointer(captionPtr)),
		uintptr(mbYesNo|mbIconWarning|mbDefaultButton2|mbSetForeground),
	)
	return int(ret) == idYes
}
