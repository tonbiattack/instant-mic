package server

import (
	"net"
	"strings"
	"testing"
	"time"
)

func samplePayload() QRPayload {
	return QRPayload{
		Host:        "192.168.1.20",
		Port:        8765,
		Fingerprint: strings.Repeat("ab", 32),
		Secret:      "0123456789abcdefghijklmn",
		ServerID:    "srv-1",
		ServerName:  "デスクPC",
		Expires:     time.UnixMilli(1_700_000_000_000),
	}
}

func TestQRPayloadRoundTrip(t *testing.T) {
	original := samplePayload()
	raw := original.String()
	if !strings.HasPrefix(raw, "instantmic://pair?") {
		t.Fatalf("unexpected scheme: %s", raw)
	}

	parsed, err := ParseQRPayload(raw)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Host != original.Host || parsed.Port != original.Port {
		t.Fatalf("address = %s:%d", parsed.Host, parsed.Port)
	}
	if parsed.Fingerprint != original.Fingerprint {
		t.Fatalf("fingerprint = %q", parsed.Fingerprint)
	}
	if parsed.Secret != original.Secret {
		t.Fatalf("secret = %q", parsed.Secret)
	}
	if parsed.ServerName != original.ServerName {
		t.Fatalf("serverName = %q", parsed.ServerName)
	}
	if !parsed.Expires.Equal(original.Expires) {
		t.Fatalf("expires = %v", parsed.Expires)
	}
}

func TestQRPayloadRejectsBadInput(t *testing.T) {
	good := samplePayload()
	cases := map[string]string{
		"wrong scheme":    "https://pair?h=1.2.3.4",
		"no params":       QRScheme + "://pair?",
		"bad version":     strings.Replace(good.String(), "v=1", "v=9", 1),
		"missing host":    strings.Replace(good.String(), "h="+good.Host, "h=", 1),
		"bad port":        strings.Replace(good.String(), "p=8765", "p=99999", 1),
		"bad fingerprint": strings.Replace(good.String(), "f="+good.Fingerprint, "f=zz", 1),
		"short secret":    strings.Replace(good.String(), "t="+good.Secret, "t=short", 1),
	}
	for name, raw := range cases {
		if _, err := ParseQRPayload(raw); err == nil {
			t.Errorf("%s: ParseQRPayload(%q) succeeded", name, raw)
		}
	}
}

func TestQRPayloadExpiry(t *testing.T) {
	payload := samplePayload()
	expires := payload.Expires
	if payload.Expired(expires.Add(-time.Second)) {
		t.Fatal("payload expired before its time")
	}
	if !payload.Expired(expires.Add(time.Minute)) {
		t.Fatal("payload did not expire")
	}
	// A little clock skew between the PC and the phone is tolerated.
	if payload.Expired(expires.Add(10 * time.Second)) {
		t.Fatal("expiry tolerance too tight for clock skew")
	}
}

func TestQRModulesRender(t *testing.T) {
	modules := samplePayload().QRModules()
	lines := RenderQR(modules, 1)
	if len(lines) < 20 {
		t.Fatalf("rendered %d lines", len(lines))
	}
	// Two module rows share one text line, so the code stays roughly square
	// and small enough to read on a normal console.
	if want := (len(modules)+1)/2 + 2; len(lines) != want {
		t.Fatalf("rendered %d lines, want %d for %d module rows", len(lines), want, len(modules))
	}
	width := -1
	for _, line := range lines {
		// Each module is one rune, so every line must be the same length.
		if n := len([]rune(line)); width < 0 {
			width = n
		} else if n != width {
			t.Fatalf("ragged QR: %d != %d", n, width)
		}
	}
	if want := len(modules[0]) + 2; width != want {
		t.Fatalf("rendered %d columns, want %d", width, want)
	}
	joined := strings.Join(lines, "\n")
	if !strings.ContainsAny(joined, "\u2580\u2584\u2588") {
		t.Fatal("QR contains no dark modules")
	}
}

func TestPrimaryHostPrefersLANAddress(t *testing.T) {
	ip := net.ParseIP(primaryHost([]net.IP{
		net.ParseIP("127.0.0.1"),
		net.ParseIP("169.254.1.1"),
		net.ParseIP("192.168.0.7"),
	}))
	if ip == nil || ip.String() != "192.168.0.7" {
		t.Fatalf("primaryHost = %v, want 192.168.0.7", ip)
	}
	if got := primaryHost(nil); got == "" {
		t.Fatal("primaryHost returned empty for no addresses")
	}
}
