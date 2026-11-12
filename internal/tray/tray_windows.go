//go:build windows

// Package tray shows the receiver's status in the Windows notification area.
//
// The tray is optional: the console UI keeps working, and a machine without
// Explorer (or a headless test run) simply never calls into this package.
package tray

import (
	"encoding/binary"
	"sync"

	"github.com/energye/systray"
)

// Menu is the set of actions a tray icon offers.
type Menu struct {
	// PairLabel is shown on the pairing item, e.g. "ペアリング (123456)".
	PairLabel string
	// OnPair opens a pairing window.
	OnPair func()
	// OnShowQR prints the pairing QR code in the console.
	OnShowQR func()
	// OnCancel cancels the current pairing window.
	OnCancel func()
	// OnQuit stops the receiver.
	OnQuit func()
}

// Run starts the tray loop and blocks until the user quits. It must be called
// on the main goroutine on Windows.
func Run(m Menu) {
	systray.Run(func() { onReady(m) }, func() {})
}

// Quit asks a running tray loop to exit. It is safe to call when no tray is
// running.
func Quit() {
	defer func() { _ = recover() }()
	systray.Quit()
}

func onReady(m Menu) {
	systray.SetIcon(iconData())
	systray.SetTitle("instant-mic")
	systray.SetTooltip("instant-mic receiver")

	pairItem := systray.AddMenuItem(m.PairLabel, "ペアリングを開始")
	qrItem := systray.AddMenuItem("QRコードを表示", "表示中のペアリングQRを再表示")
	cancelItem := systray.AddMenuItem("ペアリングを中止", "開いているペアリングを中止")
	systray.AddSeparator()
	quitItem := systray.AddMenuItem("終了", "受信アプリを終了")

	var once sync.Once
	quit := func() {
		once.Do(func() {
			if m.OnQuit != nil {
				m.OnQuit()
			}
			systray.Quit()
		})
	}

	pairItem.Click(func() {
		if m.OnPair != nil {
			m.OnPair()
		}
	})
	qrItem.Click(func() {
		if m.OnShowQR != nil {
			m.OnShowQR()
		}
	})
	cancelItem.Click(func() {
		if m.OnCancel != nil {
			m.OnCancel()
		}
	})
	quitItem.Click(quit)
}

// iconData builds a 16x16 ICO at runtime: a teal rounded square with a white
// microphone dot, so the receiver needs no asset file next to the exe.
func iconData() []byte {
	const size = 16
	const headerLen = 6 + 16
	const dibLen = 40
	xorLen := size * size * 4
	andLen := 4 * ((size + 31) / 32) * size
	buf := make([]byte, headerLen+dibLen+xorLen+andLen)

	// ICONDIR
	binary.LittleEndian.PutUint16(buf[2:], 1) // type: icon
	binary.LittleEndian.PutUint16(buf[4:], 1) // one image
	// ICONDIRENTRY
	buf[6] = size
	buf[7] = size
	binary.LittleEndian.PutUint16(buf[10:], 1)  // planes
	binary.LittleEndian.PutUint16(buf[12:], 32) // bits per pixel
	binary.LittleEndian.PutUint32(buf[14:], uint32(dibLen+xorLen+andLen))
	binary.LittleEndian.PutUint32(buf[18:], headerLen)

	// BITMAPINFOHEADER (height doubled: XOR image + AND mask)
	dib := buf[headerLen:]
	binary.LittleEndian.PutUint32(dib[0:], dibLen)
	binary.LittleEndian.PutUint32(dib[4:], size)
	binary.LittleEndian.PutUint32(dib[8:], size*2)
	binary.LittleEndian.PutUint16(dib[12:], 1)
	binary.LittleEndian.PutUint16(dib[14:], 32)

	// Pixels are BGRA, bottom-up.
	pixels := dib[dibLen:]
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			offset := ((size-1-y)*size + x) * 4
			b, g, r, a := pixelAt(x, y, size)
			pixels[offset+0] = b
			pixels[offset+1] = g
			pixels[offset+2] = r
			pixels[offset+3] = a
		}
	}
	// The AND mask is fully transparent-to-opaque via the alpha channel.
	return buf
}

// pixelAt draws the icon shape. Coordinates are 0-based; the result is BGRA.
func pixelAt(x, y, size int) (b, g, r, a byte) {
	// Rounded background.
	const radius = 3
	dx, dy := 0, 0
	if x < radius {
		dx = radius - x
	} else if x >= size-radius {
		dx = x - (size - radius - 1)
	}
	if y < radius {
		dy = radius - y
	} else if y >= size-radius {
		dy = y - (size - radius - 1)
	}
	if dx*dx+dy*dy > radius*radius {
		return 0, 0, 0, 0
	}

	// White capsule (microphone body) in the middle.
	cx, cy := float64(x)-7.5, float64(y)-6.0
	if cx*cx/9.0+(cy*cy)/16.0 <= 1.0 {
		return 255, 255, 255, 255
	}
	// White stand below the capsule.
	if y >= 11 && y <= 12 && x >= 7 && x <= 8 {
		return 255, 255, 255, 255
	}
	if y == 13 && x >= 5 && x <= 10 {
		return 255, 255, 255, 255
	}
	return 0x8a, 0x6b, 0x14, 255 // teal-ish (#146b8a)
}
