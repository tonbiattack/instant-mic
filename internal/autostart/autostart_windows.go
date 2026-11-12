//go:build windows

// Package autostart manages the per-user "run at login" registration.
//
// The registration is a small launcher in the user's Startup folder rather than
// a registry Run value: it is visible, easy to remove, and does not look like
// hidden persistence. Nothing is installed system-wide and no admin rights are
// needed.
package autostart

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const launcherName = "instant-mic.cmd"

// ErrUnsupported is returned when the executable path cannot be determined.
var ErrUnsupported = errors.New("autostart is unavailable")

// StartupDir returns the per-user Startup folder.
func StartupDir() (string, error) {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		return "", ErrUnsupported
	}
	return filepath.Join(appData, `Microsoft\Windows\Start Menu\Programs\Startup`), nil
}

// LauncherPath is the file created when autostart is enabled.
func LauncherPath() (string, error) {
	dir, err := StartupDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, launcherName), nil
}

// Enabled reports whether the receiver is registered to start at login.
func Enabled() (bool, error) {
	path, err := LauncherPath()
	if err != nil {
		return false, err
	}
	_, err = os.Stat(path)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return false, err
}

// Set enables or disables startup at login for the current executable.
func Set(enable bool) error {
	path, err := LauncherPath()
	if err != nil {
		return err
	}
	if !enable {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}

	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnsupported, err)
	}
	if resolved, err := filepath.Abs(exe); err == nil {
		exe = resolved
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	// `start ""` detaches the console-less tray process from the launcher.
	content := strings.Join([]string{
		"@echo off",
		`start "" "` + exe + `" --tray`,
		"",
	}, "\r\n")
	return os.WriteFile(path, []byte(content), 0o600)
}
