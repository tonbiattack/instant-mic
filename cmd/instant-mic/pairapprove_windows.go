//go:build windows

package main

import (
	"sync"

	"github.com/tonbiattack/instant-mic/internal/server"
)

// promptMu serializes console prompts. Two prompts interleaving their output
// would make it impossible to tell which device is being approved, and this is
// exactly the decision where that matters.
var promptMu sync.Mutex

// pairRequestApprover decides how a phone's pairing request reaches the user.
//
// Approval is always a human action, but the two modes have different places to
// ask:
//
//   - console mode: a prompt on the console
//   - tray mode:    a message box, because there is no console to type into
//
// Either way, anything short of an explicit yes denies.
func pairRequestApprover(trayMode bool) func(server.PairRequest) bool {
	if trayMode {
		return func(req server.PairRequest) bool {
			return askPairRequestDialog(req.DeviceName, req.RemoteHost)
		}
	}
	return func(req server.PairRequest) bool {
		promptMu.Lock()
		defer promptMu.Unlock()
		return askPairRequest(consoleRequest{
			DeviceName: req.DeviceName,
			RemoteHost: req.RemoteHost,
		})
	}
}
