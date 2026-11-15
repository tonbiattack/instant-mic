//go:build windows

package main

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

// askPairRequest shows a phone's pairing request on the console and waits for a
// decision.
//
// This is the only place a phone-initiated request can be approved. It is
// deliberately a human action with the facts on screen (device name, address),
// because anything on the LAN can send a request. The default is "no": a bare
// Enter, a closed stdin, or a timeout all deny.
//
// A yes is accepted only when the user types it, and a second confirmation is
// not needed because the prompt already names the device and address.
func askPairRequest(req consoleRequest) bool {
	fmt.Println()
	fmt.Println("  ── ペアリング要求 ─────────────────────────")
	fmt.Printf("  端末: %s\n", req.DeviceName)
	fmt.Printf("  から: %s\n", req.RemoteHost)
	fmt.Println("  この端末をペアリングしますか？")
	fmt.Print("  [y] 許可 / [n] 拒否（10秒で自動拒否）: ")
	return readYesNo(10 * time.Second)
}

// consoleRequest mirrors server.PairRequest without importing the server
// package from this small helper, keeping the prompt easy to test.
type consoleRequest struct {
	DeviceName string
	RemoteHost string
}

// readYesNo reads one line with a timeout. Anything other than "y"/"yes"
// denies, including EOF and a timeout.
func readYesNo(timeout time.Duration) bool {
	type answer struct{ text string }
	ch := make(chan answer, 1)

	var once sync.Once
	askOnce := func() {
		once.Do(func() {
			var line string
			// Scanner is created per call: a shared reader would swallow input
			// meant for the console loop.
			if _, err := fmt.Scanln(&line); err != nil {
				ch <- answer{}
				return
			}
			ch <- answer{text: line}
		})
	}
	go askOnce()

	select {
	case a := <-ch:
		text := strings.ToLower(strings.TrimSpace(a.text))
		return text == "y" || text == "yes"
	case <-time.After(timeout):
		fmt.Println("  （応答がないため拒否しました）")
		return false
	}
}
