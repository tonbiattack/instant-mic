package server

import (
	"fmt"
	"strings"
)

// RenderQR draws a QR module grid with block characters.
//
// The console UI uses this instead of an image so the code is visible over SSH,
// in Windows Terminal, and in a redirected log. One module is one character
// wide and half a character tall (two modules share a cell via upper/lower half
// blocks), which keeps modules roughly square in a monospace font while keeping
// the whole code to about a quarter of the area of a two-character-per-module
// rendering.
func RenderQR(modules [][]bool, border int) []string {
	if len(modules) == 0 {
		return nil
	}
	if border < 0 {
		border = 0
	}
	width := len(modules[0])
	rows := (len(modules) + 1) / 2
	lines := make([]string, 0, rows+border*2)

	blank := strings.Repeat(" ", width+border*2)
	for i := 0; i < border; i++ {
		lines = append(lines, blank)
	}
	for r := 0; r < rows; r++ {
		top := modules[r*2]
		var bottom []bool
		if r*2+1 < len(modules) {
			bottom = modules[r*2+1]
		}
		var b strings.Builder
		b.Grow(width + border*2)
		b.WriteString(strings.Repeat(" ", border))
		for x := 0; x < width; x++ {
			upper := x < len(top) && top[x]
			lower := x < len(bottom) && bottom[x]
			switch {
			case upper && lower:
				b.WriteString("\u2588") // both halves dark
			case upper:
				b.WriteString("\u2580") // upper half dark
			case lower:
				b.WriteString("\u2584") // lower half dark
			default:
				b.WriteString(" ")
			}
		}
		b.WriteString(strings.Repeat(" ", border))
		lines = append(lines, b.String())
	}
	for i := 0; i < border; i++ {
		lines = append(lines, blank)
	}
	return lines
}

// RenderQRText is RenderQR plus a short caption for the console.
func (p QRPayload) RenderQRText() string {
	lines := RenderQR(p.QRModules(), 1)
	if len(lines) == 0 {
		return "QRコードを生成できませんでした"
	}
	var b strings.Builder
	b.WriteString("Androidアプリの「QRでペアリング」で読み取ってください\n")
	for _, line := range lines {
		b.WriteString(line)
		b.WriteString("\n")
	}
	b.WriteString(fmt.Sprintf("接続先: %s:%d\n", p.Host, p.Port))
	return b.String()
}
