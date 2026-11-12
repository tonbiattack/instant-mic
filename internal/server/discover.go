package server

import (
	"context"
	"strings"
	"time"

	"github.com/grandcat/zeroconf"
)

// DiscoveredReceiver is a receiver found through mDNS.
type DiscoveredReceiver struct {
	Name        string
	ServerID    string
	Host        string
	Port        int
	Fingerprint string
	HasTLS      bool
}

// BrowseReceivers looks for receivers on the LAN for at most timeout.
//
// It is used by the development client and the tests; the Android app uses its
// own platform resolver. Discovery only reveals the address and fingerprint, so
// it can never be used to control an unpaired PC.
func BrowseReceivers(timeout time.Duration) ([]DiscoveredReceiver, error) {
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	resolver, err := zeroconf.NewResolver(nil)
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var found []DiscoveredReceiver
	seen := map[string]bool{}
	entries := make(chan *zeroconf.ServiceEntry, 16)
	go func() {
		for entry := range entries {
			receiver := DiscoveredReceiver{
				Name: entry.Instance,
				Host: firstAddress(entry),
				Port: entry.Port,
			}
			for _, txt := range entry.Text {
				key, value, ok := strings.Cut(txt, "=")
				if !ok {
					continue
				}
				switch key {
				case "id":
					receiver.ServerID = value
				case "name":
					receiver.Name = value
				case "fp":
					receiver.Fingerprint = value
				case "tls":
					receiver.HasTLS = value != "0"
				}
			}
			if receiver.ServerID == "" || seen[receiver.ServerID] {
				continue
			}
			seen[receiver.ServerID] = true
			found = append(found, receiver)
		}
	}()

	if err := resolver.Browse(ctx, ServiceType, "local.", entries); err != nil {
		return nil, err
	}
	<-ctx.Done()

	return found, nil
}

func firstAddress(entry *zeroconf.ServiceEntry) string {
	if len(entry.AddrIPv4) > 0 {
		return entry.AddrIPv4[0].String()
	}
	if len(entry.AddrIPv6) > 0 {
		return entry.AddrIPv6[0].String()
	}
	return ""
}
