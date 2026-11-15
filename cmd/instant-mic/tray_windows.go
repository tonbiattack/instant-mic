//go:build windows

package main

import (
	"fmt"
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
	// The tooltip is refreshed from the tray's ready callback instead of here:
	// systray rejects updates before the icon exists, which logged a warning at
	// startup for no benefit.

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
		OnReady: func() {
			// Called once the icon exists, which is the first moment the
			// tooltip can be set.
			refreshStatus(srv, store)
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

// openQRImage shows the pairing QR in our own window.
//
// A shell viewer opened a separate app that could land behind other windows and
// had to be closed by hand. Our window comes to the front, shows the code beside
// the QR, and is closed when pairing finishes.
func openQRImage(payload server.QRPayload, code string) {
	showPairingWindow(payload, code)
}
