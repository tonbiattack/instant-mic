//go:build windows

package main

import (
	"bytes"
	"fmt"
	"image"
	"image/png"
	"runtime"
	"sync"
	"syscall"
	"unsafe"

	"github.com/tonbiattack/instant-mic/internal/server"
)

// showPairingWindow displays the pairing QR in a small window owned by the
// receiver, instead of handing a PNG to the default image viewer.
//
// The viewer had two problems: it opens behind other windows, and it is a
// separate app the user has to find and close. This window is ours, comes to
// the front, shows the six-digit code next to the QR, and closes itself when
// pairing completes.
//
// Only one window exists at a time; showing it again updates the existing one.
type pairingWindow struct {
	mu      sync.Mutex
	hwnd    uintptr
	qrImage *bitmap
	code    string
	message string
}

var pairingWin = &pairingWindow{}

// bitmap holds a DIB ready for StretchDIBits.
type bitmap struct {
	bits   []byte
	width  int
	height int
}

var (
	gdi32                = syscall.NewLazyDLL("gdi32.dll")
	procCreateWindowExW  = user32.NewProc("CreateWindowExW")
	procDefWindowProcW   = user32.NewProc("DefWindowProcW")
	procRegisterClassEx  = user32.NewProc("RegisterClassExW")
	procDestroyWindow    = user32.NewProc("DestroyWindow")
	procShowWindow       = user32.NewProc("ShowWindow")
	procUpdateWindow     = user32.NewProc("UpdateWindow")
	procSetForeground2   = user32.NewProc("SetForegroundWindow")
	procBeginPaint       = user32.NewProc("BeginPaint")
	procEndPaint         = user32.NewProc("EndPaint")
	procGetClientRect    = user32.NewProc("GetClientRect")
	procCreateFontW      = gdi32.NewProc("CreateFontW")
	procSelectObject     = gdi32.NewProc("SelectObject")
	procTextOutW         = gdi32.NewProc("TextOutW")
	procStretchDIBits    = gdi32.NewProc("StretchDIBits")
	procDeleteObject     = gdi32.NewProc("DeleteObject")
)

const (
	wsOverlappedWindow = 0x00CF0000
	csHRedraw          = 0x0002
	csVRedraw          = 0x0001
	wmDestroy          = 0x0002
	wmPaint            = 0x000F
	wmClose            = 0x0010
	colorWindow        = 5
	swShow             = 5
)

type wndClassEx struct {
	size       uint32
	style      uint32
	wndProc    uintptr
	clsExtra   int32
	wndExtra   int32
	instance   uintptr
	icon       uintptr
	cursor     uintptr
	background uintptr
	menuName   *uint16
	className  *uint16
	iconSm     uintptr
}

type rect struct{ left, top, right, bottom int32 }

type paintStruct struct {
	hdc       uintptr
	erase     int32
	rcPaint   rect
	restore   int32
	incUpdate int32
	reserved  [32]byte
}

type bitmapInfoHeader struct {
	size          uint32
	width         int32
	height        int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xPelsPerMeter int32
	yPelsPerMeter int32
	clrUsed       uint32
	clrImportant  uint32
}

var (
	pairingClassOnce sync.Once
	pairingClassName = syscall.StringToUTF16Ptr("InstantMicPairing")
)

// showPairingWindow opens (or updates) the pairing window. payload is the QR
// content; code is the six-digit fallback.
func showPairingWindow(payload server.QRPayload, code string) {
	pngBytes, err := payload.QRPNG(256)
	if err != nil {
		// Fall back to the shell viewer so pairing is never blocked.
		openQRImage(payload, code)
		return
	}
	img, err := png.Decode(bytes.NewReader(pngBytes))
	if err != nil {
		openQRImage(payload, code)
		return
	}
	qr := toBitmap(img)

	pairingWin.mu.Lock()
	pairingWin.qrImage = qr
	pairingWin.code = code
	pairingWin.message = fmt.Sprintf("接続先: %s:%d", payload.Host, payload.Port)
	hwnd := pairingWin.hwnd
	pairingWin.mu.Unlock()

	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()

		if hwnd == 0 {
			pairingWin.create()
			return
		}
		// Existing window: repaint with the new code and bring it forward.
		user32.NewProc("InvalidateRect").Call(hwnd, 0, 1)
		procSetForeground2.Call(hwnd)
	}()
}

// create builds the window and runs its message loop on the current thread.
func (w *pairingWindow) create() {
	pairingClassOnce.Do(func() {
		wc := wndClassEx{
			style:      csHRedraw | csVRedraw,
			wndProc:    syscall.NewCallback(pairingProc),
			background: uintptr(colorWindow) + 1,
			className:  pairingClassName,
		}
		wc.size = uint32(unsafe.Sizeof(wc))
		procRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))
	})

	title, _ := syscall.UTF16PtrFromString("instant-mic — ペアリング")
	hwnd, _, _ := procCreateWindowExW.Call(
		0,
		uintptr(unsafe.Pointer(pairingClassName)),
		uintptr(unsafe.Pointer(title)),
		uintptr(wsOverlappedWindow),
		0x80000000, // CW_USEDEFAULT
		0x80000000,
		520, 520,		0, 0, 0, 0,
	)
	if hwnd == 0 {
		return
	}

	w.mu.Lock()
	w.hwnd = hwnd
	w.mu.Unlock()

	procShowWindow.Call(hwnd, swShow)
	procUpdateWindow.Call(hwnd)
	procSetForeground2.Call(hwnd)

	// Standard message loop; ends when the window is destroyed.
	var msg [7]uintptr
	for {
		r, _, _ := user32.NewProc("GetMessageW").Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		user32.NewProc("TranslateMessage").Call(uintptr(unsafe.Pointer(&msg)))
		user32.NewProc("DispatchMessageW").Call(uintptr(unsafe.Pointer(&msg)))
	}

	w.mu.Lock()
	w.hwnd = 0
	w.qrImage = nil
	w.mu.Unlock()
}

// closePairingWindow hides the window once pairing succeeded.
func closePairingWindow() {
	pairingWin.mu.Lock()
	hwnd := pairingWin.hwnd
	pairingWin.mu.Unlock()
	if hwnd != 0 {
		user32.NewProc("PostMessageW").Call(hwnd, wmClose, 0, 0)
	}
}

// pairingProc handles paint and close.
func pairingProc(hwnd uintptr, msg uint32, wParam, lParam uintptr) uintptr {
	switch msg {
	case wmPaint:
		var ps paintStruct
		hdc, _, _ := procBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
		pairingWin.paint(hdc)
		procEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
		return 0
	case wmClose:
		procDestroyWindow.Call(hwnd)
		return 0
	case wmDestroy:
		return 0
	}
	ret, _, _ := procDefWindowProcW.Call(hwnd, uintptr(msg), wParam, lParam)
	return ret
}

// paint draws the QR, the code, and the instructions.
func (w *pairingWindow) paint(hdc uintptr) {
	w.mu.Lock()
	qr := w.qrImage
	code := w.code
	message := w.message
	w.mu.Unlock()

	var rc rect
	procGetClientRect.Call(w.hwnd, uintptr(unsafe.Pointer(&rc)))
	width := int(rc.right - rc.left)

	if qr != nil {
		// Centre the QR with a margin.
		size := 256
		x := (width - size) / 2
		if x < 0 {
			x = 0
		}
		info := bitmapInfoHeader{
			size:        uint32(unsafe.Sizeof(bitmapInfoHeader{})),
			width:       int32(qr.width),
			height:      -int32(qr.height), // top-down
			planes:      1,
			bitCount:    32,
			compression: 0,
		}
		procStretchDIBits.Call(
			hdc,
			uintptr(x), 20, uintptr(size), uintptr(size),
			0, 0, uintptr(qr.width), uintptr(qr.height),
			uintptr(unsafe.Pointer(&qr.bits[0])),
			uintptr(unsafe.Pointer(&info)),
			0,          // DIB_RGB_COLORS
			0x00CC0020, // SRCCOPY
		)
	}

	font, _ := syscall.UTF16PtrFromString("Segoe UI")
	hFont, _, _ := procCreateFontW.Call(
		uintptr(28), 0, 0, 0, 600, 0, 0, 0, 1, 0, 0, 0, 0,
		uintptr(unsafe.Pointer(font)),
	)
	if hFont != 0 {
		old, _, _ := procSelectObject.Call(hdc, hFont)
		defer func() {
			procSelectObject.Call(hdc, old)
			procDeleteObject.Call(hdc, hFont)
		}()
	}

	y := 300
	if code != "" {
		text, _ := syscall.UTF16FromString("コード: " + code)
		procTextOutW.Call(hdc, 30, uintptr(y), uintptr(unsafe.Pointer(&text[0])), uintptr(len(text)-1))
		y += 40
	}
	if message != "" {
		text, _ := syscall.UTF16FromString(message)
		procTextOutW.Call(hdc, 30, uintptr(y), uintptr(unsafe.Pointer(&text[0])), uintptr(len(text)-1))
		y += 40
	}
	hint, _ := syscall.UTF16FromString("スマホの「QRコードを読み取る」でスキャンしてください")
	procTextOutW.Call(hdc, 30, uintptr(y), uintptr(unsafe.Pointer(&hint[0])), uintptr(len(hint)-1))
}

// toBitmap converts a decoded image to BGRA for StretchDIBits.
func toBitmap(img image.Image) *bitmap {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	bits := make([]byte, w*h*4)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			r, g, bl, a := img.At(b.Min.X+x, b.Min.Y+y).RGBA()
			o := (y*w + x) * 4
			bits[o+0] = byte(bl >> 8)
			bits[o+1] = byte(g >> 8)
			bits[o+2] = byte(r >> 8)
			bits[o+3] = byte(a >> 8)
		}
	}
	return &bitmap{bits: bits, width: w, height: h}
}
