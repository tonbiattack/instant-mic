//go:build windows && (amd64 || arm64)

// Package input injects text and key events into the focused Windows window.
//
// Injection uses SendInput with KEYEVENTF_UNICODE so Japanese text does not
// depend on the active IME, and never uses the clipboard.
package input

import (
	"errors"
	"fmt"
	"runtime"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"

	"github.com/tonbiattack/instant-mic/internal/speech"
)

const (
	inputKeyboard     = 1
	keyeventfExtended = 0x0001
	keyeventfKeyUp    = 0x0002
	keyeventfUnicode  = 0x0004

	vkShift   = 0x10
	vkControl = 0x11
	vkMenu    = 0x12
	vkLWin    = 0x5B
	vkReturn  = 0x0D

	processQueryLimitedInformation = 0x1000
)

// vkKeys maps the allowlisted key names from internal/speech to virtual-key
// codes. Keys that Windows treats as extended also need the extended flag.
var vkKeys = map[string]struct {
	code     uint16
	extended bool
}{
	"enter":     {0x0D, false},
	"tab":       {0x09, false},
	"escape":    {0x1B, false},
	"backspace": {0x08, false},
	"delete":    {0x2E, true},
	"insert":    {0x2D, true},
	"space":     {0x20, false},
	"up":        {0x26, true},
	"down":      {0x28, true},
	"left":      {0x25, true},
	"right":     {0x27, true},
	"home":      {0x24, true},
	"end":       {0x23, true},
	"pageup":    {0x21, true},
	"pagedown":  {0x22, true},
}

func modifierVK(m speech.Modifier) (uint16, bool, bool) {
	switch m {
	case speech.ModCtrl:
		return vkControl, false, true
	case speech.ModShift:
		return vkShift, false, true
	case speech.ModAlt:
		return vkMenu, false, true
	case speech.ModWin:
		return vkLWin, true, true
	}
	return 0, false, false
}

// keyVK resolves a single key name to its virtual-key code.
func keyVK(key string) (uint16, bool, bool) {
	if k, ok := vkKeys[key]; ok {
		return k.code, k.extended, true
	}
	if len(key) == 1 {
		r := rune(key[0])
		switch {
		case r >= 'a' && r <= 'z':
			return uint16(r - 'a' + 'A'), false, true
		case r >= '0' && r <= '9':
			return uint16(r), false, true
		}
	}
	if len(key) >= 2 && len(key) <= 3 && key[0] == 'f' {
		var n int
		for _, r := range key[1:] {
			if r < '0' || r > '9' {
				return 0, false, false
			}
			n = n*10 + int(r-'0')
		}
		if n >= 1 && n <= 24 {
			return uint16(0x70 + n - 1), false, true
		}
	}
	return 0, false, false
}

type keybdInput struct {
	Vk        uint16
	Scan      uint16
	Flags     uint32
	Time      uint32
	ExtraInfo uintptr
}

// inputUnion mirrors the Win32 INPUT struct. The union is sized for
// MOUSEINPUT (40 bytes total on 64-bit Windows) so SendInput receives the
// cbSize it expects.
type inputUnion struct {
	Type uint32
	_    uint32
	Ki   keybdInput
	_    [8]byte
}

var (
	user32               = windows.NewLazySystemDLL("user32.dll")
	procSendInput        = user32.NewProc("SendInput")
	procGetForeground    = user32.NewProc("GetForegroundWindow")
	procGetWindowTextW   = user32.NewProc("GetWindowTextW")
	procGetWindowTextLen = user32.NewProc("GetWindowTextLengthW")
	procGetWindowPID     = user32.NewProc("GetWindowThreadProcessId")
	procGetKeyState      = user32.NewProc("GetKeyState")

	kernel32                 = windows.NewLazySystemDLL("kernel32.dll")
	procOpenProcess          = kernel32.NewProc("OpenProcess")
	procQueryFullProcessName = kernel32.NewProc("QueryFullProcessImageNameW")
)

// WindowsInserter implements Inserter through SendInput. It is not safe for
// concurrent use; the server serializes calls.
type WindowsInserter struct{}

// New returns the platform inserter.
func New() Inserter { return &WindowsInserter{} }

func sendInputs(in []inputUnion) error {
	if len(in) == 0 {
		return nil
	}
	n, _, err := procSendInput.Call(
		uintptr(len(in)),
		uintptr(unsafe.Pointer(&in[0])),
		unsafe.Sizeof(in[0]),
	)
	if n != 0 {
		return nil
	}
	if err != nil {
		return fmt.Errorf("SendInput: %w", err)
	}
	return errors.New("SendInput was blocked by the system")
}

func unicodeInputs(text string) []inputUnion {
	// A carriage return maps to Enter; normalise line breaks so multi-line text
	// behaves the same way regardless of how the client encoded it.
	text = strings.ReplaceAll(text, "\r\n", "\r")
	text = strings.ReplaceAll(text, "\n", "\r")

	units := windows.StringToUTF16(text)
	if n := len(units); n > 0 && units[n-1] == 0 {
		units = units[:n-1]
	}
	inputs := make([]inputUnion, 0, len(units)*2)
	for _, u := range units {
		inputs = append(inputs,
			inputUnion{Type: inputKeyboard, Ki: keybdInput{Scan: u, Flags: keyeventfUnicode}},
			inputUnion{Type: inputKeyboard, Ki: keybdInput{Scan: u, Flags: keyeventfUnicode | keyeventfKeyUp}},
		)
	}
	return inputs
}

// InsertText types text into the focused window using Unicode key events.
func (w *WindowsInserter) InsertText(text string) error {
	if err := w.checkTarget(); err != nil {
		return err
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	return sendInputs(unicodeInputs(text))
}

func keyDown(vk uint16, extended bool) inputUnion {
	var flags uint32
	if extended {
		flags |= keyeventfExtended
	}
	return inputUnion{Type: inputKeyboard, Ki: keybdInput{Vk: vk, Flags: flags}}
}

func keyUp(vk uint16, extended bool) inputUnion {
	flags := uint32(keyeventfKeyUp)
	if extended {
		flags |= keyeventfExtended
	}
	return inputUnion{Type: inputKeyboard, Ki: keybdInput{Vk: vk, Flags: flags}}
}

func (w *WindowsInserter) checkTarget() error {
	st := w.Status()
	if !st.HasFocus {
		return ErrNoFocus
	}
	if st.Elevated {
		return ErrElevatedTarget
	}
	return nil
}

// PressKey presses a validated key sequence count times.
func (w *WindowsInserter) PressKey(seq speech.Sequence, count int) error {
	if len(seq) == 0 {
		return errors.New("empty key sequence")
	}
	if len(seq) > speech.MaxSequenceSteps {
		return errors.New("key sequence is too long")
	}
	if count < 1 {
		count = 1
	}
	if count > speech.MaxRepeat {
		count = speech.MaxRepeat
	}

	inputs := make([]inputUnion, 0, len(seq)*12*count)
	for i := 0; i < count; i++ {
		for _, combo := range seq {
			chord, err := comboInputs(combo)
			if err != nil {
				return err
			}
			inputs = append(inputs, chord...)
		}
	}

	if err := w.checkTarget(); err != nil {
		return err
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	return sendInputs(inputs)
}

// comboInputs builds the down/up events for one chord: modifiers down, key
// down, key up, modifiers up (in reverse order).
func comboInputs(combo speech.Combo) ([]inputUnion, error) {
	type downKey struct {
		vk       uint16
		extended bool
	}
	var mods []downKey
	for _, m := range combo.Mods {
		vk, extended, ok := modifierVK(m)
		if !ok {
			return nil, fmt.Errorf("unsupported modifier %q", m)
		}
		mods = append(mods, downKey{vk, extended})
	}
	vk, extended, ok := keyVK(combo.Key)
	if !ok {
		return nil, fmt.Errorf("unsupported key %q", combo.Key)
	}

	events := make([]inputUnion, 0, len(mods)*2+2)
	for _, m := range mods {
		events = append(events, keyDown(m.vk, m.extended))
	}
	events = append(events, keyDown(vk, extended), keyUp(vk, extended))
	for i := len(mods) - 1; i >= 0; i-- {
		events = append(events, keyUp(mods[i].vk, mods[i].extended))
	}
	return events, nil
}

// Status inspects the foreground window.
func (w *WindowsInserter) Status() Status {
	hwnd, _, _ := procGetForeground.Call()
	if hwnd == 0 {
		return Status{}
	}

	length, _, _ := procGetWindowTextLen.Call(hwnd)
	title := ""
	if length > 0 {
		buf := make([]uint16, length+1)
		procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		title = windows.UTF16ToString(buf)
	}

	var pid uint32
	procGetWindowPID.Call(hwnd, uintptr(unsafe.Pointer(&pid)))

	status := Status{HasFocus: true, Title: title}
	if pid != 0 {
		status.Process = processName(pid)
	}
	status.Elevated = cannotInject(pid)
	return status
}

func processName(pid uint32) string {
	h, _, _ := procOpenProcess.Call(processQueryLimitedInformation, 0, uintptr(pid))
	if h == 0 {
		return ""
	}
	defer windows.CloseHandle(windows.Handle(h))
	buf := make([]uint16, windows.MAX_PATH)
	size := uint32(len(buf))
	ok, _, _ := procQueryFullProcessName.Call(
		h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)),
	)
	if ok == 0 {
		return ""
	}
	return windows.UTF16ToString(buf[:size])
}

// cannotInject reports whether UIPI would block input to pid. A non-elevated
// process cannot type into an elevated one; running elevated ourselves lifts
// that restriction.
func cannotInject(pid uint32) bool {
	if pid == 0 || currentProcessElevated() {
		return false
	}
	h, _, _ := procOpenProcess.Call(processQueryLimitedInformation, 0, uintptr(pid))
	if h == 0 {
		// Access denied usually means a protected or elevated target.
		return true
	}
	defer windows.CloseHandle(windows.Handle(h))

	var token windows.Token
	if err := windows.OpenProcessToken(windows.Handle(h), windows.TOKEN_QUERY, &token); err != nil {
		return true
	}
	defer token.Close()

	if currentProcessElevated() {
		return false
	}
	return token.IsElevated()
}

func currentProcessElevated() bool {
	token, err := windows.OpenCurrentProcessToken()
	if err != nil {
		return false
	}
	defer token.Close()
	return token.IsElevated()
}

// KeyState reports whether a virtual key is currently down; used by tests and
// diagnostics.
func KeyState(vk uint16) bool {
	state, _, _ := procGetKeyState.Call(uintptr(vk))
	return int16(state) < 0
}
