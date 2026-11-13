//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/tonbiattack/instant-mic/internal/config"
	"github.com/tonbiattack/instant-mic/internal/server"
	"github.com/tonbiattack/instant-mic/internal/tray"
)

// runTray keeps the receiver running with only a notification-area icon.
//
// At login there is no console window, so the pairing QR is written to an image
// file and opened on demand instead of being printed.
func runTray(srv *server.Server, store *config.Store, ttl time.Duration, openPairingAtStart bool) error {
	quit := make(chan struct{})
	var quitOnce bool

	showPairing := func() {
		code, _, qr, err := srv.BeginPairingQR(ttl)
		if err != nil {
			return
		}
		openQRImage(qr.Payload, code)
	}

	if openPairingAtStart {
		showPairing()
	}

	tray.Run(tray.Menu{
		PairLabel: "ペアリングを開始",
		OnPair:    showPairing,
		OnShowQR: func() {
			if qr, ok := srv.PairingQR(); ok {
				openQRImage(qr.Payload, "")
			}
		},
		OnCancel: srv.CancelPairing,
		OnQuit: func() {
			if !quitOnce {
				quitOnce = true
				close(quit)
			}
		},
	})
	<-quit
	_ = store
	return nil
}

// openQRImage writes the QR as a PNG and opens it with the default viewer. The
// file is overwritten on purpose: only the newest (still valid) QR is useful.
func openQRImage(payload server.QRPayload, code string) {
	png, err := payload.QRPNG(256)
	if err != nil {
		return
	}
	path := filepath.Join(os.TempDir(), "instant-mic-pairing.png")
	if err := os.WriteFile(path, png, 0o600); err != nil {
		return
	}
	_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", path).Start()
}
