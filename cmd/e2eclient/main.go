// Command e2eclient exercises a running receiver over TLS and prints results.
// It is a development helper, not part of the shipped app.
package main

import (
	"crypto/tls"
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
)

func main() {
	host := flag.String("host", "127.0.0.1", "receiver host")
	port := flag.Int("port", 8765, "receiver port")
	code := flag.String("code", "", "pairing code")
	text := flag.String("text", "", "text to send")
	key := flag.String("key", "", "key to send")
	useTLS := flag.Bool("tls", true, "use HTTPS/WSS")
	tokenFile := flag.String("token-file", "", "reuse/remember the device token here")
	flag.Parse()

	schemeHTTP, schemeWS := "https", "wss"
	if !*useTLS {
		schemeHTTP, schemeWS = "http", "ws"
	}

	base := fmt.Sprintf("%s:%d", *host, *port)
	client := &http.Client{
		Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}},
		Timeout:   5 * time.Second,
	}

	var token string
	if *tokenFile != "" {
		if data, err := os.ReadFile(*tokenFile); err == nil {
			token = strings.TrimSpace(string(data))
		}
	}

	if token == "" {
		payload := fmt.Sprintf(`{"code":%q,"deviceId":"e2e-device","deviceName":"e2e"}`, *code)
		resp, err := client.Post(schemeHTTP+"://"+base+"/pair", "application/json", strings.NewReader(payload))
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
			fail("no token (status %d)", resp.StatusCode)
		}
		token = pr.Token
		if *tokenFile != "" {
			_ = os.WriteFile(*tokenFile, []byte(token), 0o600)
		}
		fmt.Println("paired")
	}

	dialer := &websocket.Dialer{
		TLSClientConfig:  &tls.Config{InsecureSkipVerify: true},
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
			if err := ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: id, Text: chunk}); err != nil {
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
