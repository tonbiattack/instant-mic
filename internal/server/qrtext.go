package server

import (
	"fmt"
	"strings"
)

// RenderQR draws a QR module grid with block characters.
//
// The console UI uses this instead of an image so the code is visible over SSH,
// in Windows Terminal, and in a redirected log. Each module is two characters
// wide so the result is roughly square in a monospace font.
func RenderQR(modules [][]bool, border int) []string {
	if len(modules) == 0 {
		return nil
	}
	if border < 0 {
		border = 0
	}
	width := len(modules[0])
	lines := make([]string, 0, len(modules)+border*2)

	blank := strings.Repeat("  ", width+border*2)
	for i := 0; i < border; i++ {
		lines = append(lines, blank)
	}
	for _, row := range modules {
		var b strings.Builder
		b.Grow((len(row) + border*2) * 3)
		b.WriteString(strings.Repeat("  ", border))
		for _, dark := range row {
			if dark {
				// Full block twice renders as a solid 2x1 module.
				b.WriteString("\u2588\u2588")
			} else {
				b.WriteString("  ")
			}
		}
		b.WriteString(strings.Repeat("  ", border))
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
