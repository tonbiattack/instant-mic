package protocol

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestDecodeAuth(t *testing.T) {
	good := `{"type":"auth","protocol":1,"deviceId":"dev-1","token":"secret"}`
	auth, err := DecodeAuth([]byte(good))
	if err != nil {
		t.Fatal(err)
	}
	if auth.DeviceID != "dev-1" || auth.Token != "secret" {
		t.Fatalf("auth = %+v", auth)
	}

	bad := []string{
		`{"type":"text","protocol":1,"deviceId":"dev-1","token":"x"}`,
		`{"type":"auth","protocol":2,"deviceId":"dev-1","token":"x"}`,
		`{"type":"auth","protocol":1,"token":"x"}`,
		`{"type":"auth","protocol":1,"deviceId":"dev-1"}`,
		`not json`,
	}
	for _, s := range bad {
		if _, err := DecodeAuth([]byte(s)); err == nil {
			t.Errorf("DecodeAuth(%q) succeeded", s)
		}
	}
}

func TestDecodeText(t *testing.T) {
	msg, err := DecodeText([]byte(`{"type":"text","id":"op-1","text":"こんにちは"}`))
	if err != nil {
		t.Fatal(err)
	}
	if msg.ID != "op-1" || msg.Text != "こんにちは" {
		t.Fatalf("msg = %+v", msg)
	}

	bad := []string{
		`{"type":"key","id":"op-1","text":"x"}`,
		`{"type":"text","text":"x"}`,
		`{"type":"text","id":"bad id!","text":"x"}`,
		`{"type":"text","id":"op-1","text":""}`,
	}
	for _, s := range bad {
		if _, err := DecodeText([]byte(s)); err == nil {
			t.Errorf("DecodeText(%q) succeeded", s)
		}
	}
}

func TestDecodeKey(t *testing.T) {
	msg, err := DecodeKey([]byte(`{"type":"key","id":"op-1","key":"enter"}`))
	if err != nil {
		t.Fatal(err)
	}
	if msg.Key != "enter" {
		t.Fatalf("msg = %+v", msg)
	}
	if _, err := DecodeKey([]byte(`{"type":"text","id":"op-1","key":"enter"}`)); err == nil {
		t.Fatal("wrong type accepted")
	}
	// A repeat count outside 1-5 is rejected before any injection happens.
	for _, s := range []string{
		`{"type":"key","id":"op-1","key":"backspace","count":6}`,
		`{"type":"key","id":"op-1","key":"backspace","count":-1}`,
	} {
		if _, err := DecodeKey([]byte(s)); err == nil {
			t.Errorf("DecodeKey(%q) succeeded", s)
		}
	}
}

func TestDecodeErase(t *testing.T) {
	good := `{"type":"erase","id":"op-1","count":5}`
	msg, err := DecodeErase([]byte(good))
	if err != nil {
		t.Fatal(err)
	}
	if msg.Count != 5 {
		t.Fatalf("count = %d", msg.Count)
	}

	bad := []string{
		`{"type":"text","id":"op-1","count":5}`,
		`{"type":"erase","count":5}`,
		`{"type":"erase","id":"op-1"}`,
		`{"type":"erase","id":"op-1","count":0}`,
		`{"type":"erase","id":"op-1","count":-3}`,
		`{"type":"erase","id":"op-1","count":4001}`,
		`{"type":"erase","id":"bad id","count":1}`,
	}
	for _, s := range bad {
		if _, err := DecodeErase([]byte(s)); err == nil {
			t.Errorf("DecodeErase(%q) succeeded", s)
		}
	}
}

func TestValidID(t *testing.T) {
	for _, id := range []string{"op-1", "ABC_123", "", "bad id", strings.Repeat("a", 65)} {
		want := id != "" && !strings.ContainsAny(id, " ") && len(id) <= 64
		if got := ValidID(id); got != want {
			t.Errorf("ValidID(%q) = %v, want %v", id, got, want)
		}
	}
}

func TestAckJSONShape(t *testing.T) {
	ack := Ack{Type: TypeAck, ID: "op-1", OK: true, Applied: "enter"}
	data, err := json.Marshal(ack)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"type":"ack"`, `"id":"op-1"`, `"ok":true`, `"applied":"enter"`} {
		if !strings.Contains(string(data), want) {
			t.Errorf("ack JSON %s missing %s", data, want)
		}
	}
	// Empty optional fields should stay out of the payload.
	if strings.Contains(string(data), "duplicate") {
		t.Errorf("ack JSON includes duplicate=false: %s", data)
	}
}
