package config

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func openTemp(t *testing.T) *Store {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestOpenCreatesDefaults(t *testing.T) {
	s := openTemp(t)
	cfg := s.Snapshot()
	if cfg.ServerID == "" || cfg.ServerName == "" {
		t.Fatalf("defaults not applied: %+v", cfg)
	}
	if cfg.ListenAddr != ":8765" {
		t.Fatalf("ListenAddr = %q", cfg.ListenAddr)
	}
	if cfg.CommandPrefix != "コマンド" {
		t.Fatalf("CommandPrefix = %q", cfg.CommandPrefix)
	}
	info, err := os.Stat(s.Path())
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		t.Errorf("config permissions = %v, want owner-only", info.Mode().Perm())
	}
}

func TestPairAndAuthenticate(t *testing.T) {
	s := openTemp(t)
	token, err := s.PairDevice("dev-1", "Pixel")
	if err != nil {
		t.Fatal(err)
	}
	if len(token) < 20 {
		t.Fatalf("token too short: %q", token)
	}
	if _, ok := s.Authenticate("dev-1", token); !ok {
		t.Fatal("valid token rejected")
	}
	if _, ok := s.Authenticate("dev-1", "wrong"); ok {
		t.Fatal("invalid token accepted")
	}
	if _, ok := s.Authenticate("other", token); ok {
		t.Fatal("token accepted for another device")
	}
}

func TestTokenNotStoredInPlaintext(t *testing.T) {
	s := openTemp(t)
	token, err := s.PairDevice("dev-1", "Pixel")
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(s.Path())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), token) {
		t.Fatal("token written to disk in plaintext")
	}
}

func TestRepairReplacesToken(t *testing.T) {
	s := openTemp(t)
	first, _ := s.PairDevice("dev-1", "Pixel")
	second, _ := s.PairDevice("dev-1", "Pixel")
	if first == second {
		t.Fatal("token was not rotated")
	}
	if _, ok := s.Authenticate("dev-1", first); ok {
		t.Fatal("old token still valid")
	}
	if _, ok := s.Authenticate("dev-1", second); !ok {
		t.Fatal("new token invalid")
	}
	if got := len(s.Devices()); got != 1 {
		t.Fatalf("devices = %d", got)
	}
}

func TestUnpair(t *testing.T) {
	s := openTemp(t)
	_, _ = s.PairDevice("dev-1", "Pixel")
	removed, err := s.Unpair("dev-1")
	if err != nil || !removed {
		t.Fatalf("Unpair = %v, %v", removed, err)
	}
	if got := len(s.Devices()); got != 0 {
		t.Fatalf("devices = %d", got)
	}
	removed, err = s.Unpair("dev-1")
	if err != nil || removed {
		t.Fatalf("second Unpair = %v, %v", removed, err)
	}
}

func TestPersistedAcrossReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	token, _ := s.PairDevice("dev-1", "Pixel")
	serverID := s.Snapshot().ServerID

	again, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if again.Snapshot().ServerID != serverID {
		t.Fatal("server id changed")
	}
	if _, ok := again.Authenticate("dev-1", token); !ok {
		t.Fatal("pairing did not persist")
	}
}

func TestSetCommandPrefix(t *testing.T) {
	s := openTemp(t)
	if err := s.SetCommandPrefix("  マイク  "); err != nil {
		t.Fatal(err)
	}
	if got := s.Snapshot().CommandPrefix; got != "マイク" {
		t.Fatalf("CommandPrefix = %q", got)
	}
	if err := s.SetCommandPrefix("   "); err == nil {
		t.Fatal("empty prefix accepted")
	}
}
