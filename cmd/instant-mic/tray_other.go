//go:build !windows

package main

import (
	"time"

	"github.com/tonbiattack/instant-mic/internal/config"
	"github.com/tonbiattack/instant-mic/internal/server"
)

func runTray(srv *server.Server, store *config.Store, ttl time.Duration, openPairingAtStart bool) error {
	return nil
}
