package server

import (
	"context"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/grandcat/zeroconf"

	"github.com/tonbiattack/instant-mic/internal/netutil"
)

// ServiceType is the DNS-SD service advertised on the LAN.
//
// "_instantmic._tcp" is deliberately LAN-only and carries no secret: it tells a
// phone which address, port and certificate fingerprint to try next. The phone
// still has to prove it is paired (or complete a pairing window), so discovery
// can never be used to drive the PC.
const ServiceType = "_instantmic._tcp"

// advertiseInfo is what a phone needs in order to reconnect without the user
// typing an address.
type advertiseInfo struct {
	ServerID    string
	ServerName  string
	Port        int
	Fingerprint string
	HasTLS      bool
}

// advertiser owns the mDNS registration and can be stopped.
type advertiser struct {
	mu    sync.Mutex
	stop_ context.CancelFunc
	done  chan struct{}
}

func (a *advertiser) stop() {
	a.mu.Lock()
	cancel := a.stop_
	a.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if a.done != nil {
		select {
		case <-a.done:
		case <-time.After(2 * time.Second):
		}
	}
}

// startAdvertiser registers the DNS-SD service. Failure is not fatal: the app
// still works with a manually entered address, so a blocked multicast network
// only downgrades discovery.
func startAdvertiser(info advertiseInfo, emit func(level, msg string)) *advertiser {
	if info.ServerID == "" || info.Port <= 0 {
		return nil
	}
	txt := []string{
		"id=" + info.ServerID,
		"name=" + info.ServerName,
		"fp=" + info.Fingerprint,
	}
	if info.HasTLS {
		txt = append(txt, "tls=1")
	} else {
		txt = append(txt, "tls=0")
	}
	hostname := "instant-mic-" + info.ServerID + ".local."
	if len(hostname) > 63 {
		hostname = hostname[:63] + ".local."
	}

	// The library needs explicit addresses: with nil it registers the host's
	// name but never answers address queries, so phones cannot resolve it.
	var ips []string
	for _, ip := range netutil.LocalIPs() {
		ips = append(ips, ip.String())
	}

	server, err := zeroconf.RegisterProxy(
		info.ServerName+" ("+info.ServerID+")",
		ServiceType,
		"local.",
		info.Port,
		hostname,
		ips,
		txt,
		nil,
	)
	if err != nil {
		if emit != nil {
			emit("warn", "mDNS 広告を開始できません（手動接続は利用できます）: "+err.Error())
		}
		return nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		<-ctx.Done()
		server.Shutdown()
	}()
	if emit != nil {
		emit("info", "LAN内の自動検出を開始しました ("+ServiceType+")")
	}
	return &advertiser{stop_: cancel, done: done}
}

func portOf(addr net.Addr) int {
	if addr == nil {
		return 0
	}
	_, port, err := net.SplitHostPort(addr.String())
	if err != nil {
		return 0
	}
	n, err := strconv.Atoi(port)
	if err != nil {
		return 0
	}
	return n
}
