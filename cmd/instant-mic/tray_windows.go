//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/tonbiattack/instant-mic/internal/autostart"
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
		waitForPairingAndNotify(srv, store, quit)
	}

	if openPairingAtStart {
		showPairing()
	}
	refreshStatus(srv, store)

	tray.Run(tray.Menu{
		PairLabel: "ペアリングを開始",
		OnPair:    showPairing,
		OnShowQR: func() {
			if qr, ok := srv.PairingQR(); ok {
				openQRImage(qr.Payload, "")
				return
			}
			// Pairing expired: starting a new window is what the user meant.
			showPairing()
		},
		OnCancel: srv.CancelPairing,
		AutostartOn: func() bool {
			enabled, err := autostart.Enabled()
			return err == nil && enabled
		},
		OnToggleAutostart: func() error {
			enabled, err := autostart.Enabled()
			if err != nil {
				return err
			}
			return autostart.Set(!enabled)
		},
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

// refreshStatus puts the current state in the tray tooltip, so the user can
// tell whether the phone is connected without opening anything.
func refreshStatus(srv *server.Server, store *config.Store) {
	count := srv.ClientCount()
	status := "未接続"
	if count > 0 {
		status = fmt.Sprintf("接続中（%d台）", count)
	}
	paired := len(store.Devices())
	tray.UpdateTooltip(fmt.Sprintf("instant-mic receiver — %s / ペアリング済み %d台", status, paired))
}

// waitForPairingAndNotify polls until the pairing window closes, then refreshes
// the tooltip once a phone has connected. It returns immediately in a goroutine
// so the tray stays responsive.
func waitForPairingAndNotify(srv *server.Server, store *config.Store, quit <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()
		deadline := time.Now().Add(15 * time.Minute)
		for {
			select {
			case <-quit:
				return
			case <-ticker.C:
				refreshStatus(srv, store)
				if srv.ClientCount() > 0 || time.Now().After(deadline) {
					return
				}
			}
		}
	}()
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
