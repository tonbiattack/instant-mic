// Command e2eclient exercises a running receiver over TLS and prints results.
// It is a development helper, not part of the shipped app.
package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gorilla/websocket"

	"github.com/tonbiattack/instant-mic/internal/protocol"
	"github.com/tonbiattack/instant-mic/internal/server"
)

func main() {
	host := flag.String("host", "127.0.0.1", "receiver host")
	port := flag.Int("port", 8765, "receiver port")
	code := flag.String("code", "", "pairing code")
	qr := flag.String("qr", "", "pairing QR payload (copied from the console)")
	discover := flag.Bool("discover", false, "list receivers advertised on the LAN and exit")
	text := flag.String("text", "", "text to send (separate chunks with |)")
	literal := flag.Bool("literal", false, "send text as settled phrases that are never commands")
	key := flag.String("key", "", "key to send")
	useTLS := flag.Bool("tls", true, "use HTTPS/WSS")
	tokenFile := flag.String("token-file", "", "reuse/remember the device token here")
	flag.Parse()

	if *discover {
		listDiscovered()
		return
	}

	schemeHTTP, schemeWS := "https", "wss"
	if !*useTLS {
		schemeHTTP, schemeWS = "http", "ws"
	}

	// A scanned QR payload carries its own address and fingerprint; using it
	// here reproduces exactly what the phone does.
	var payload server.QRPayload
	var pinned string
	if *qr != "" {
		parsed, err := server.ParseQRPayload(*qr)
		if err != nil {
			fail("qr: %v", err)
		}
		payload = parsed
		*host = payload.Host
		*port = payload.Port
		pinned = payload.Fingerprint
	}

	base := fmt.Sprintf("%s:%d", *host, *port)
	tlsConfig := tlsConfigFor(pinned, *useTLS)
	client := &http.Client{
		Transport: &http.Transport{TLSClientConfig: tlsConfig},
		Timeout:   5 * time.Second,
	}

	var token string
	if *tokenFile != "" {
		if data, err := os.ReadFile(*tokenFile); err == nil {
			token = strings.TrimSpace(string(data))
		}
	}

	if token == "" {
		var payloadJSON string
		if *qr != "" {
			payloadJSON = fmt.Sprintf(`{"qrSecret":%q,"deviceId":"e2e-device","deviceName":"e2e"}`, payload.Secret)
		} else {
			payloadJSON = fmt.Sprintf(`{"code":%q,"deviceId":"e2e-device","deviceName":"e2e"}`, *code)
		}
		resp, err := client.Post(schemeHTTP+"://"+base+"/pair", "application/json", strings.NewReader(payloadJSON))
		if err != nil {
			fail("pair: %v", err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var pr struct {
			Token string `json:"token"`
		}
		if err := json.Unmarshal(body, &pr); err != nil {
			fail("decode pair (status %d): %s", resp.StatusCode, body)
		}
		if pr.Token == "" {
			fail("no token (status %d): %s", resp.StatusCode, body)
		}
		token = pr.Token
		if *tokenFile != "" {
			_ = os.WriteFile(*tokenFile, []byte(token), 0o600)
		}
		fmt.Println("paired")
	}

	dialer := &websocket.Dialer{
		TLSClientConfig:  tlsConfig,
		HandshakeTimeout: 5 * time.Second,
	}
	ws, _, err := dialer.Dial(schemeWS+"://"+base+"/ws", nil)
	if err != nil {
		fail("dial: %v", err)
	}
	defer ws.Close()

	auth := protocol.Auth{
		Type: protocol.TypeAuth, Protocol: protocol.Version,
		DeviceID: "e2e-device", DeviceName: "e2e", Token: token,
	}
	if err := ws.WriteJSON(auth); err != nil {
		fail("auth: %v", err)
	}
	var ready protocol.Ready
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	if err := ws.ReadJSON(&ready); err != nil {
		fail("ready: %v", err)
	}
	fmt.Println("connected:", ready.ServerName)

	if *text != "" {
		for i, chunk := range strings.Split(*text, "|") {
			id := fmt.Sprintf("e2e-%d-%d", time.Now().UnixNano(), i)
			msg := protocol.Text{Type: protocol.TypeText, ID: id, Text: chunk, Literal: *literal}
			if err := ws.WriteJSON(msg); err != nil {
				fail("text: %v", err)
			}
			ack := readAck(ws)
			fmt.Printf("text ack: ok=%v applied=%s error=%q elapsed=%dms\n", ack.OK, ack.Applied, ack.Error, ack.ElapsedMS)
		}
	}
	if *key != "" {
		id := fmt.Sprintf("e2e-key-%d", time.Now().UnixNano())
		if err := ws.WriteJSON(protocol.Key{Type: protocol.TypeKey, ID: id, Key: *key}); err != nil {
			fail("key: %v", err)
		}
		ack := readAck(ws)
		fmt.Printf("key ack: ok=%v applied=%s error=%q\n", ack.OK, ack.Applied, ack.Error)
	}
}

// tlsConfigFor mirrors the app: a pinned fingerprint when one is known,
// otherwise accept the self-signed certificate and rely on the pairing token.
func tlsConfigFor(pinned string, useTLS bool) *tls.Config {
	cfg := &tls.Config{MinVersion: tls.VersionTLS12}
	if !useTLS {
		return cfg
	}
	if pinned == "" {
		cfg.InsecureSkipVerify = true
		return cfg
	}
	expected := strings.ToLower(pinned)
	cfg.InsecureSkipVerify = true // verified manually below, like the phone
	cfg.VerifyConnection = func(state tls.ConnectionState) error {
		if len(state.PeerCertificates) == 0 {
			return fmt.Errorf("no peer certificate")
		}
		sum := sha256.Sum256(state.PeerCertificates[0].Raw)
		actual := hex.EncodeToString(sum[:])
		if actual != expected {
			return fmt.Errorf("certificate fingerprint mismatch: got %s", actual)
		}
		return nil
	}
	return cfg
}

func listDiscovered() {
	found, err := server.BrowseReceivers(4 * time.Second)
	if err != nil {
		fail("discover: %v", err)
	}
	if len(found) == 0 {
		fmt.Println("no receiver found")
		return
	}
	for _, r := range found {
		fmt.Printf("%s id=%s host=%s port=%d fp=%s tls=%v\n",
			r.Name, r.ServerID, r.Host, r.Port, r.Fingerprint, r.HasTLS)
	}
}

func readAck(ws *websocket.Conn) protocol.Ack {
	_ = ws.SetReadDeadline(time.Now().Add(5 * time.Second))
	var ack protocol.Ack
	if err := ws.ReadJSON(&ack); err != nil {
		fail("ack: %v", err)
	}
	return ack
}

func fail(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
