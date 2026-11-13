package server

import (
	"crypto/tls"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/tonbiattack/instant-mic/internal/config"
	"github.com/tonbiattack/instant-mic/internal/input"
	"github.com/tonbiattack/instant-mic/internal/netutil"
	"github.com/tonbiattack/instant-mic/internal/protocol"
	"github.com/tonbiattack/instant-mic/internal/speech"
)

type fakeInserter struct {
	mu        sync.Mutex
	texts     []string
	keys      []keyPress
	erased    []int
	status    input.Status
	failText  error
	failKey   error
	failErase error
}

type keyPress struct {
	sequence string
	count    int
}

func (f *fakeInserter) InsertText(text string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.status.HasFocus {
		return input.ErrNoFocus
	}
	if f.failText != nil {
		return f.failText
	}
	f.texts = append(f.texts, text)
	return nil
}

func (f *fakeInserter) PressKey(seq speech.Sequence, count int) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.status.HasFocus {
		return input.ErrNoFocus
	}
	if f.failKey != nil {
		return f.failKey
	}
	f.keys = append(f.keys, keyPress{sequence: seq.String(), count: count})
	return nil
}

func (f *fakeInserter) Status() input.Status { return f.status }

func (f *fakeInserter) EraseText(count int) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.status.HasFocus {
		return input.ErrNoFocus
	}
	if f.failErase != nil {
		return f.failErase
	}
	f.erased = append(f.erased, count)
	return nil
}

func (f *fakeInserter) erasedCounts() []int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]int(nil), f.erased...)
}

func (f *fakeInserter) snapshot() ([]string, []keyPress) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.texts...), append([]keyPress(nil), f.keys...)
}

type testEnv struct {
	srv     *Server
	store   *config.Store
	ins     *fakeInserter
	wsURL   string
	httpURL string
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()
	dir := t.TempDir()
	store, err := config.Open(dir + "/config.json")
	if err != nil {
		t.Fatal(err)
	}
	ins := &fakeInserter{status: input.Status{HasFocus: true, Title: "メモ帳"}}
	advertise := false
	srv := New(Config{Store: store, Inserter: ins, Advertise: &advertise})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv.StartListener(ln)
	t.Cleanup(func() { _ = srv.Close() })

	base := "http://" + ln.Addr().String()
	return &testEnv{srv: srv, store: store, ins: ins, wsURL: "ws://" + ln.Addr().String() + "/ws", httpURL: base}
}

func (e *testEnv) pair(t *testing.T, deviceID string) string {
	t.Helper()
	code, _, err := e.srv.BeginPairing(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	body := strings.NewReader(`{"code":"` + code + `","deviceId":"` + deviceID + `","deviceName":"テスト端末"}`)
	resp, err := http.Post(e.httpURL+"/pair", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair status = %d", resp.StatusCode)
	}
	var pr pairResponse
	if err := json.NewDecoder(resp.Body).Decode(&pr); err != nil {
		t.Fatal(err)
	}
	return pr.Token
}

func (e *testEnv) connect(t *testing.T, deviceID, token string) *websocket.Conn {
	t.Helper()
	ws, _, err := websocket.DefaultDialer.Dial(e.wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ws.Close() })
	auth := protocol.Auth{
		Type: protocol.TypeAuth, Protocol: protocol.Version,
		DeviceID: deviceID, DeviceName: "テスト端末", Token: token,
	}
	if err := ws.WriteJSON(auth); err != nil {
		t.Fatal(err)
	}
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	var ready protocol.Ready
	if err := ws.ReadJSON(&ready); err != nil {
		t.Fatal(err)
	}
	if ready.Type != protocol.TypeReady {
		t.Fatalf("expected ready, got %q", ready.Type)
	}
	return ws
}

func readAck(t *testing.T, ws *websocket.Conn) protocol.Ack {
	t.Helper()
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	var ack protocol.Ack
	if err := ws.ReadJSON(&ack); err != nil {
		t.Fatal(err)
	}
	return ack
}

func TestPairAndTypeText(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	if err := ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "op-1", Text: "こんにちは"}); err != nil {
		t.Fatal(err)
	}
	ack := readAck(t, ws)
	if !ack.OK || ack.ID != "op-1" || ack.Applied != "text" {
		t.Fatalf("unexpected ack: %+v", ack)
	}
	texts, _ := env.ins.snapshot()
	if len(texts) != 1 || texts[0] != "こんにちは" {
		t.Fatalf("typed = %v", texts)
	}
}

func TestCommandIsNotTyped(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	if err := ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "cmd-1", Text: "コマンド、送信"}); err != nil {
		t.Fatal(err)
	}
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "enter" {
		t.Fatalf("unexpected ack: %+v", ack)
	}
	texts, keys := env.ins.snapshot()
	if len(texts) != 0 {
		t.Fatalf("command was typed as text: %v", texts)
	}
	if len(keys) != 1 || keys[0].sequence != "enter" || keys[0].count != 1 {
		t.Fatalf("keys = %v", keys)
	}
}

func TestSentenceWithSendIsTyped(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	msg := "この文章を送信してください"
	if err := ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "t-1", Text: msg}); err != nil {
		t.Fatal(err)
	}
	ack := readAck(t, ws)
	if !ack.OK {
		t.Fatalf("ack = %+v", ack)
	}
	texts, keys := env.ins.snapshot()
	if len(keys) != 0 {
		t.Fatalf("unexpected key: %v", keys)
	}
	if len(texts) != 1 || texts[0] != msg {
		t.Fatalf("texts = %v", texts)
	}
}

func TestDuplicateOperationExecutesOnce(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	msg := protocol.Text{Type: protocol.TypeText, ID: "dup-1", Text: "一度だけ"}
	for i := 0; i < 2; i++ {
		if err := ws.WriteJSON(msg); err != nil {
			t.Fatal(err)
		}
		ack := readAck(t, ws)
		if !ack.OK {
			t.Fatalf("ack = %+v", ack)
		}
		if i == 1 && !ack.Duplicate {
			t.Fatalf("second ack should be marked duplicate: %+v", ack)
		}
	}
	texts, _ := env.ins.snapshot()
	if len(texts) != 1 {
		t.Fatalf("typed %d times, want 1", len(texts))
	}
}

func TestUnknownDeviceRejected(t *testing.T) {
	env := newTestEnv(t)
	ws, _, err := websocket.DefaultDialer.Dial(env.wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	_ = ws.WriteJSON(protocol.Auth{
		Type: protocol.TypeAuth, Protocol: protocol.Version,
		DeviceID: "stranger", Token: "nope",
	})
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	var e protocol.Error
	if err := ws.ReadJSON(&e); err != nil {
		t.Fatal(err)
	}
	if e.Code != "auth_failed" {
		t.Fatalf("error = %+v", e)
	}
}

func TestWrongPairingCode(t *testing.T) {
	env := newTestEnv(t)
	_, _, err := env.srv.BeginPairing(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	body := strings.NewReader(`{"code":"000000","deviceId":"dev-x"}`)
	resp, err := http.Post(env.httpURL+"/pair", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", resp.StatusCode)
	}
}

func TestPairingClosedByDefault(t *testing.T) {
	env := newTestEnv(t)
	body := strings.NewReader(`{"code":"123456","deviceId":"dev-x"}`)
	resp, err := http.Post(env.httpURL+"/pair", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Fatalf("status = %d, want 410", resp.StatusCode)
	}
}

func (e *testEnv) pairWithQR(t *testing.T, deviceID string) string {
	t.Helper()
	_, _, qr, err := e.srv.BeginPairingQR(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	payload := qr.Payload
	body := strings.NewReader(`{"qrSecret":"` + payload.Secret + `","deviceId":"` + deviceID + `","deviceName":"QR端末"}`)
	resp, err := http.Post(e.httpURL+"/pair", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("QR pair status = %d", resp.StatusCode)
	}
	var pr pairResponse
	if err := json.NewDecoder(resp.Body).Decode(&pr); err != nil {
		t.Fatal(err)
	}
	return pr.Token
}

func TestQRPairingGrantsToken(t *testing.T) {
	env := newTestEnv(t)
	token := env.pairWithQR(t, "qr-dev")
	if token == "" {
		t.Fatal("no token issued")
	}
	// The issued token must work on the WebSocket exactly like a typed code.
	ws := env.connect(t, "qr-dev", token)
	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "qr-1", Text: "QRから入力"})
	ack := readAck(t, ws)
	if !ack.OK {
		t.Fatalf("ack = %+v", ack)
	}
}

func TestQRSecretIsSingleUse(t *testing.T) {
	env := newTestEnv(t)
	_, _, qr, err := env.srv.BeginPairingQR(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	secret := qr.Payload.Secret

	post := func(device string) int {
		body := strings.NewReader(`{"qrSecret":"` + secret + `","deviceId":"` + device + `"}`)
		resp, err := http.Post(env.httpURL+"/pair", "application/json", body)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		return resp.StatusCode
	}
	if status := post("first"); status != http.StatusOK {
		t.Fatalf("first status = %d", status)
	}
	// The QR and the six-digit code are consumed together.
	if status := post("second"); status != http.StatusGone {
		t.Fatalf("second status = %d, want 410", status)
	}
	if qr, ok := env.srv.PairingQR(); ok {
		t.Fatalf("pairing window is still open: %+v", qr.Payload)
	}
}

func TestWrongQRSecretIsRejected(t *testing.T) {
	env := newTestEnv(t)
	if _, _, _, err := env.srv.BeginPairingQR(time.Minute); err != nil {
		t.Fatal(err)
	}
	body := strings.NewReader(`{"qrSecret":"not-the-right-secret-at-all","deviceId":"dev-qr"}`)
	resp, err := http.Post(env.httpURL+"/pair", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", resp.StatusCode)
	}
}

func TestPairingQRIsUnavailableWhenClosed(t *testing.T) {
	env := newTestEnv(t)
	if _, ok := env.srv.PairingQR(); ok {
		t.Fatal("QR available with pairing closed")
	}
	env.srv.CancelPairing()
	if _, ok := env.srv.PairingQR(); ok {
		t.Fatal("QR available after cancel")
	}
}

func TestSpokenUndoRemovesLastInsertion(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "undo-1", Text: "こんにちは"})
	if ack := readAck(t, ws); !ack.OK {
		t.Fatalf("ack = %+v", ack)
	}
	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "undo-2", Text: "コマンド、言い直し"})
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "erase" {
		t.Fatalf("ack = %+v", ack)
	}

	if got := env.ins.erasedCounts(); len(got) != 1 || got[0] != 5 {
		t.Fatalf("erased = %v, want [5]", got)
	}
	texts, _ := env.ins.snapshot()
	if len(texts) != 1 {
		t.Fatalf("undo was typed as text: %v", texts)
	}
}

func TestLiteralPhraseIsNeverACommand(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	// A settled phrase is typed literally, never interpreted: a phrase that is
	// also a key command must not press anything on the PC.
	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "l1", Text: "送信", Literal: true})
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "text" {
		t.Fatalf("literal phrase = %+v", ack)
	}
	texts, keys := env.ins.snapshot()
	if len(keys) != 0 {
		t.Fatalf("literal phrase pressed keys: %v", keys)
	}
	if len(texts) != 1 || texts[0] != "送信" {
		t.Fatalf("texts = %v", texts)
	}
}

func TestLiteralPhraseStartingWithWakeWordIsRefused(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	// The phone keeps incremental input off for commands, so a partial phrase
	// that starts like the wake word means the sides disagree.
	for _, text := range []string{"コマンド", "コマ", "コマンド、送信"} {
		_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "w1", Text: text, Literal: true})
		ack := readAck(t, ws)
		if ack.OK {
			t.Fatalf("literal %q was typed", text)
		}
	}
	if texts, _ := env.ins.snapshot(); len(texts) != 0 {
		t.Fatalf("texts = %v, want none", texts)
	}
}

func TestIncrementalPhrasesUndoAsOneInsertion(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	for i, phrase := range []string{"いちです。", "にです。"} {
		_ = ws.WriteJSON(protocol.Text{
			Type: protocol.TypeText, ID: "p" + string(rune('a'+i)), Text: phrase, Literal: true,
		})
		if ack := readAck(t, ws); !ack.OK {
			t.Fatalf("phrase %q = %+v", phrase, ack)
		}
	}
	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "undo-all", Text: "コマンド、言い直し"})
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "erase" {
		t.Fatalf("undo = %+v", ack)
	}
	// 5 + 4 = 9 runes across both phrases.
	if got := env.ins.erasedCounts(); len(got) != 1 || got[0] != 9 {
		t.Fatalf("erased = %v, want [9]", got)
	}
}

func TestUndoIsSingleUse(t *testing.T) {	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "u1", Text: "abc"})
	readAck(t, ws)
	for i, id := range []string{"u2", "u3"} {
		_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: id, Text: "コマンド、言い直し"})
		ack := readAck(t, ws)
		if i == 0 && (!ack.OK || ack.Applied != "erase") {
			t.Fatalf("first undo = %+v", ack)
		}
		if i == 1 && (ack.OK || ack.Error == "") {
			t.Fatalf("second undo should fail: %+v", ack)
		}
	}
	if got := env.ins.erasedCounts(); len(got) != 1 {
		t.Fatalf("erased %d times, want 1: %v", len(got), got)
	}
}

func TestUndoAfterKeyActionIsRefused(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "k1", Text: "abc"})
	readAck(t, ws)
	_ = ws.WriteJSON(protocol.Key{Type: protocol.TypeKey, ID: "k2", Key: "enter"})
	readAck(t, ws)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "k3", Text: "コマンド、言い直し"})
	ack := readAck(t, ws)
	if ack.OK {
		t.Fatalf("undo after Enter should be refused: %+v", ack)
	}
	if got := env.ins.erasedCounts(); len(got) != 0 {
		t.Fatalf("erased despite a key action: %v", got)
	}
}

func TestEraseMessageCountMustMatch(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "e1", Text: "hello"})
	readAck(t, ws)

	// A count that does not match the receiver's own record is refused.
	_ = ws.WriteJSON(protocol.Erase{Type: protocol.TypeErase, ID: "e2", Count: 2})
	ack := readAck(t, ws)
	if ack.OK {
		t.Fatalf("mismatched erase should fail: %+v", ack)
	}
	// The correct count still works afterwards.
	_ = ws.WriteJSON(protocol.Erase{Type: protocol.TypeErase, ID: "e3", Count: 5})
	ack = readAck(t, ws)
	if !ack.OK || ack.Applied != "erase" {
		t.Fatalf("matching erase = %+v", ack)
	}
	if got := env.ins.erasedCounts(); len(got) != 1 || got[0] != 5 {
		t.Fatalf("erased = %v, want [5]", got)
	}
}

func TestEraseWithoutInsertionFails(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Erase{Type: protocol.TypeErase, ID: "n1", Count: 1})
	ack := readAck(t, ws)
	if ack.OK || ack.Error == "" {
		t.Fatalf("erase with nothing to undo = %+v", ack)
	}
	if got := env.ins.erasedCounts(); len(got) != 0 {
		t.Fatalf("erased without insertion: %v", got)
	}
}

func TestEraseRejectsOutOfRangeCount(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	for i, count := range []int{0, -1, 100000} {
		id := "bad-erase-" + string(rune('a'+i))
		_ = ws.WriteJSON(protocol.Erase{Type: protocol.TypeErase, ID: id, Count: count})
		_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
		var e protocol.Error
		if err := ws.ReadJSON(&e); err != nil {
			t.Fatalf("count %d: %v", count, err)
		}
		if e.Code != "bad_erase" {
			t.Fatalf("count %d: error = %+v", count, e)
		}
	}
}

func TestBrowserOriginRejected(t *testing.T) {
	env := newTestEnv(t)
	req := httptest.NewRequest(http.MethodPost, "/pair", strings.NewReader(`{}`))
	req.Header.Set("Origin", "http://evil.example")
	rec := httptest.NewRecorder()
	env.srv.handlePair(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", rec.Code)
	}

	_, resp, err := websocket.DefaultDialer.Dial(env.wsURL, http.Header{"Origin": {"http://evil.example"}})
	if err == nil {
		t.Fatal("browser origin was allowed")
	}
	if resp != nil && resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %d, want 403", resp.StatusCode)
	}
}

func TestNoFocusReportsError(t *testing.T) {
	env := newTestEnv(t)
	env.ins.status = input.Status{}
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "nf-1", Text: "文字"})
	ack := readAck(t, ws)
	if ack.OK {
		t.Fatalf("expected failure: %+v", ack)
	}
	if ack.Error == "" {
		t.Fatal("missing error message")
	}
}

func TestKeyMessage(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Key{Type: protocol.TypeKey, ID: "k-1", Key: "shift+enter"})
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "shift+enter" {
		t.Fatalf("ack = %+v", ack)
	}
	_, keys := env.ins.snapshot()
	if len(keys) != 1 || keys[0].sequence != "shift+enter" || keys[0].count != 1 {
		t.Fatalf("keys = %v", keys)
	}
}

func TestUnsupportedKeyRejected(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	for i, bad := range []string{"ctrl+alt+delete", "hyper+x", "ctrl", "a+b"} {
		id := "k-bad-" + string(rune('a'+i))
		_ = ws.WriteJSON(protocol.Key{Type: protocol.TypeKey, ID: id, Key: bad})
		_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
		var e protocol.Error
		if err := ws.ReadJSON(&e); err != nil {
			t.Fatalf("%s: %v", bad, err)
		}
		if e.Code != "bad_key" {
			t.Fatalf("%s: error = %+v", bad, e)
		}
	}
	_, keys := env.ins.snapshot()
	if len(keys) != 0 {
		t.Fatalf("keys = %v", keys)
	}
}

func TestKeySequenceAndRepeat(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Key{
		Type: protocol.TypeKey, ID: "seq-1", Key: "home>shift+end>delete",
	})
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "home>shift+end>delete" {
		t.Fatalf("ack = %+v", ack)
	}

	_ = ws.WriteJSON(protocol.Key{Type: protocol.TypeKey, ID: "seq-2", Key: "backspace", Count: 3})
	ack = readAck(t, ws)
	if !ack.OK || ack.Applied != "backspace" {
		t.Fatalf("ack = %+v", ack)
	}

	_, keys := env.ins.snapshot()
	if len(keys) != 2 {
		t.Fatalf("keys = %v", keys)
	}
	if keys[0].sequence != "home>shift+end>delete" || keys[0].count != 1 {
		t.Fatalf("first key = %+v", keys[0])
	}
	if keys[1].sequence != "backspace" || keys[1].count != 3 {
		t.Fatalf("second key = %+v", keys[1])
	}
}

func TestVoiceCommandBackspaceIsNotTyped(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Text{Type: protocol.TypeText, ID: "cmd-bs", Text: "コマンド、削除"})
	ack := readAck(t, ws)
	if !ack.OK || ack.Applied != "backspace" {
		t.Fatalf("ack = %+v", ack)
	}
	texts, keys := env.ins.snapshot()
	if len(texts) != 0 {
		t.Fatalf("command was typed: %v", texts)
	}
	if len(keys) != 1 || keys[0].sequence != "backspace" {
		t.Fatalf("keys = %v", keys)
	}
}

func TestRepairReplacesToken(t *testing.T) {
	env := newTestEnv(t)
	first := env.pair(t, "dev-1")
	second := env.pair(t, "dev-1")
	if first == second {
		t.Fatal("re-pairing must issue a new token")
	}

	ws, _, err := websocket.DefaultDialer.Dial(env.wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	_ = ws.WriteJSON(protocol.Auth{
		Type: protocol.TypeAuth, Protocol: protocol.Version,
		DeviceID: "dev-1", Token: first,
	})
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	var e protocol.Error
	if err := ws.ReadJSON(&e); err != nil {
		t.Fatal(err)
	}
	if e.Code != "auth_failed" {
		t.Fatalf("old token still works: %+v", e)
	}
}

func TestPingPong(t *testing.T) {
	env := newTestEnv(t)
	token := env.pair(t, "dev-1")
	ws := env.connect(t, "dev-1", token)

	_ = ws.WriteJSON(protocol.Ping{Type: protocol.TypePing, ID: "p-1", SentAt: 123})
	_ = ws.SetReadDeadline(time.Now().Add(3 * time.Second))
	var pong protocol.Pong
	if err := ws.ReadJSON(&pong); err != nil {
		t.Fatal(err)
	}
	if pong.ID != "p-1" || pong.SentAt != 123 || pong.RecvAt == 0 {
		t.Fatalf("pong = %+v", pong)
	}
}

func TestTLSListener(t *testing.T) {
	dir := t.TempDir()
	store, err := config.Open(dir + "/config.json")
	if err != nil {
		t.Fatal(err)
	}
	cert, fp, err := netutil.EnsureCert(dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(fp) != 64 {
		t.Fatalf("fingerprint = %q", fp)
	}
	_, again, err := netutil.EnsureCert(dir, nil)
	if err != nil {
		t.Fatal(err)
	}
	if again != fp {
		t.Fatal("certificate was regenerated instead of reused")
	}

	ins := &fakeInserter{status: input.Status{HasFocus: true}}
	advertise := false
	srv := New(Config{
		Store:     store,
		Inserter:  ins,
		TLS:       &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
		Advertise: &advertise,
	})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv.StartListener(tls.NewListener(ln, srv.tls))
	t.Cleanup(func() { _ = srv.Close() })

	dialer := &websocket.Dialer{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, // self-signed, pinned by fingerprint
	}
	ws, _, err := dialer.Dial("wss://"+ln.Addr().String()+"/ws", nil)
	if err != nil {
		t.Fatalf("TLS dial: %v", err)
	}
	defer ws.Close()
}
