package server

import (
	"crypto/rand"
	"encoding/base64"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/tonbiattack/instant-mic/internal/netutil"
)

// QRVersion is the QR payload version. Bump it on breaking changes.
const QRVersion = 1

// QRScheme is the URI scheme carried by the pairing QR code.
const QRScheme = "instantmic"

// QRPayload is the short-lived pairing information shown as a QR code.
//
// It deliberately contains no long-term token: only the one-time QR secret that
// the receiver consumes on the first successful pairing. Scanning the code is
// therefore no more dangerous than reading the six-digit code off the screen,
// and it stops working as soon as the pairing window closes.
type QRPayload struct {
	Host        string
	Port        int
	Fingerprint string
	Secret      string
	ServerID    string
	ServerName  string
	Expires     time.Time
}

// String encodes the payload as a compact URI that a phone can scan.
func (p QRPayload) String() string {
	q := url.Values{}
	q.Set("v", strconv.Itoa(QRVersion))
	q.Set("h", p.Host)
	q.Set("p", strconv.Itoa(p.Port))
	q.Set("f", p.Fingerprint)
	q.Set("t", p.Secret)
	q.Set("s", p.ServerID)
	if p.ServerName != "" {
		q.Set("n", p.ServerName)
	}
	q.Set("e", strconv.FormatInt(p.Expires.UnixMilli(), 10))
	return QRScheme + "://pair?" + q.Encode()
}

// ErrInvalidQRPayload reports a malformed or expired pairing QR payload.
type ErrInvalidQRPayload struct{ Reason string }

func (e ErrInvalidQRPayload) Error() string { return "invalid pairing QR code: " + e.Reason }

// ParseQRPayload validates a scanned payload. The phone applies the same rules,
// and the receiver still verifies the secret, so a parsed payload is never
// trusted on its own.
func ParseQRPayload(raw string) (QRPayload, error) {
	const prefix = QRScheme + "://pair?"
	raw = strings.TrimSpace(raw)
	if !strings.HasPrefix(raw, prefix) {
		return QRPayload{}, ErrInvalidQRPayload{"unexpected scheme"}
	}
	q, err := url.ParseQuery(strings.TrimPrefix(raw, prefix))
	if err != nil {
		return QRPayload{}, ErrInvalidQRPayload{"malformed parameters"}
	}
	if q.Get("v") != strconv.Itoa(QRVersion) {
		return QRPayload{}, ErrInvalidQRPayload{"unsupported version"}
	}

	payload := QRPayload{
		Host:        q.Get("h"),
		Fingerprint: strings.ToLower(q.Get("f")),
		Secret:      q.Get("t"),
		ServerID:    q.Get("s"),
		ServerName:  q.Get("n"),
	}
	if payload.Host == "" {
		return QRPayload{}, ErrInvalidQRPayload{"missing host"}
	}
	port, err := strconv.Atoi(q.Get("p"))
	if err != nil || port < 1 || port > 65535 {
		return QRPayload{}, ErrInvalidQRPayload{"invalid port"}
	}
	payload.Port = port
	if !validFingerprint(payload.Fingerprint) {
		return QRPayload{}, ErrInvalidQRPayload{"invalid fingerprint"}
	}
	if len(payload.Secret) < 16 {
		return QRPayload{}, ErrInvalidQRPayload{"invalid pairing secret"}
	}
	expiresMS, err := strconv.ParseInt(q.Get("e"), 10, 64)
	if err != nil || expiresMS <= 0 {
		return QRPayload{}, ErrInvalidQRPayload{"invalid expiry"}
	}
	payload.Expires = time.UnixMilli(expiresMS)
	return payload, nil
}

// Expired reports whether the payload is past its expiry, allowing a little
// clock skew between the PC and the phone.
func (p QRPayload) Expired(now time.Time) bool {
	return now.After(p.Expires.Add(30 * time.Second))
}

func validFingerprint(fp string) bool {
	if len(fp) != 64 {
		return false
	}
	for _, r := range fp {
		if (r < '0' || r > '9') && (r < 'a' || r > 'f') {
			return false
		}
	}
	return true
}

// primaryHost picks an address a phone on the same LAN can reach. The QR code
// carries one host; mDNS covers the case where DHCP changes it later.
func primaryHost(ips []net.IP) string {
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil && !v4.IsLoopback() && !v4.IsLinkLocalUnicast() {
			return v4.String()
		}
	}
	if len(ips) > 0 {
		return ips[0].String()
	}
	return "127.0.0.1"
}

func newQRSecret() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// PairingQR describes the QR code for the current pairing window.
type PairingQR struct {
	Payload QRPayload
}

// PairingQR returns the current QR code without starting a pairing window. It
// reports false when pairing is not open.
func (s *Server) PairingQR() (PairingQR, bool) {
	secret := s.pairing.Secret()
	state := s.pairing.State()
	if secret == "" || !state.Open {
		return PairingQR{}, false
	}
	cfg := s.store.Snapshot()
	payload := QRPayload{
		Host:        primaryHost(netutil.LocalIPs()),
		Port:        s.Port(),
		Fingerprint: s.fingerprint,
		Secret:      secret,
		ServerID:    cfg.ServerID,
		ServerName:  cfg.ServerName,
		Expires:     state.Expires,
	}
	return PairingQR{Payload: payload}, true
}

// BeginPairingQR opens a pairing window and returns the human readable code
// together with a QR payload that completes the same pairing.
func (s *Server) BeginPairingQR(ttl time.Duration) (string, time.Time, PairingQR, error) {
	code, expires, _, err := s.pairing.OpenWithSecret(ttl)
	if err != nil {
		return "", time.Time{}, PairingQR{}, err
	}
	qr, ok := s.PairingQR()
	if !ok {
		return "", time.Time{}, PairingQR{}, ErrPairingClosed
	}
	s.emit("info", "ペアリングを開始しました（有効期限 "+expires.Format("15:04")+"）")
	return code, expires, qr, nil
}

// QRModules renders the payload as a module grid for terminal display. The
// caller draws it with block characters, which any console can show.
func (p QRPayload) QRModules() [][]bool {
	code, err := qrcode.New(p.String(), qrcode.Medium)
	if err != nil {
		return nil
	}
	return code.Bitmap()
}

// QRPNG renders the payload as a PNG image for the tray UI.
func (p QRPayload) QRPNG(size int) ([]byte, error) {
	if size < 128 || size > 2048 {
		size = 512
	}
	return qrcode.Encode(p.String(), qrcode.Medium, size)
}
