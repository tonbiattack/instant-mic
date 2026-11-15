//go:build windows

package autostart

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The launcher is a small .cmd in the Startup folder. Two properties matter and
// are easy to break silently:
//
//   - it must start the receiver in tray mode, because a login has no console
//     and a console window at every login is unwanted;
//   - it must quote the path, since "C:\Program Files\…" contains spaces and an
//     unquoted path would start the wrong thing or fail.
func TestLauncherStartsTrayMode(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", dir)

	if err := Set(true); err != nil {
		t.Fatalf("Set(true) = %v", err)
	}
	path, err := LauncherPath()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("launcher not written: %v", err)
	}
	content := string(data)

	if !strings.Contains(content, "--tray") {
		t.Errorf("launcher does not start in tray mode:\n%s", content)
	}
	if !strings.Contains(content, `"`) {
		t.Errorf("launcher does not quote the executable path:\n%s", content)
	}

	enabled, err := Enabled()
	if err != nil || !enabled {
		t.Fatalf("Enabled() = %v, %v; want true", enabled, err)
	}
}

func TestDisableRemovesLauncher(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", dir)

	if err := Set(true); err != nil {
		t.Fatalf("Set(true) = %v", err)
	}
	if err := Set(false); err != nil {
		t.Fatalf("Set(false) = %v", err)
	}
	enabled, err := Enabled()
	if err != nil {
		t.Fatal(err)
	}
	if enabled {
		t.Fatal("still enabled after Set(false)")
	}
	// Disabling twice must not fail: the tray toggle can race with a manual
	// cleanup of the Startup folder.
	if err := Set(false); err != nil {
		t.Fatalf("second Set(false) = %v", err)
	}
}

func TestEnabledWithoutLauncher(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", dir)

	enabled, err := Enabled()
	if err != nil {
		t.Fatalf("Enabled() = %v", err)
	}
	if enabled {
		t.Fatal("reported enabled before any launcher was written")
	}
	// The Startup folder may not exist yet on a fresh profile.
	if _, err := os.Stat(filepath.Join(dir, `Microsoft\Windows\Start Menu\Programs\Startup`)); err == nil {
		t.Log("startup dir exists")
	}
}
