// Package config persists receiver settings and paired devices.
//
// The file lives outside the repository in the per-user configuration
// directory and only ever contains token hashes, never the tokens themselves.
package config

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// DefaultPort is the default listening port.
const DefaultPort = 8765

// Device is a paired phone.
type Device struct {
	ID         string    `json:"id"`
	Name       string    `json:"name,omitempty"`
	TokenHash  string    `json:"tokenHash"`
	PairedAt   time.Time `json:"pairedAt"`
	LastSeenAt time.Time `json:"lastSeenAt,omitempty"`
}

// Config is the on-disk receiver configuration.
type Config struct {
	ServerID      string   `json:"serverId"`
	ServerName    string   `json:"serverName"`
	ListenAddr    string   `json:"listenAddr"`
	CommandPrefix string   `json:"commandPrefix"`
	Devices       []Device `json:"devices"`
}

// Store is a concurrency-safe configuration store.
type Store struct {
	path string
	mu   sync.Mutex
	cfg  Config
}

// NewRandomID returns a URL-safe random identifier.
func NewRandomID(bytes int) (string, error) {
	b := make([]byte, bytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// NewToken returns a fresh bearer token for a paired device.
func NewToken() (string, error) {
	return NewRandomID(32)
}

// HashToken returns the stored form of a token.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func (c *Config) applyDefaults() {
	if c.ServerName == "" {
		name, err := os.Hostname()
		if err != nil || strings.TrimSpace(name) == "" {
			name = "instant-mic"
		}
		c.ServerName = name
	}
	if c.ListenAddr == "" {
		c.ListenAddr = fmt.Sprintf(":%d", DefaultPort)
	}
	if c.CommandPrefix == "" {
		c.CommandPrefix = "コマンド"
	}
	if c.ServerID == "" {
		id, err := NewRandomID(12)
		if err == nil {
			c.ServerID = id
		}
	}
}

// DefaultPath returns the configuration path, honouring INSTANT_MIC_CONFIG.
func DefaultPath() (string, error) {
	if p := os.Getenv("INSTANT_MIC_CONFIG"); p != "" {
		return p, nil
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("locate user config dir: %w", err)
	}
	return filepath.Join(dir, "instant-mic", "config.json"), nil
}

// Open loads the configuration, creating it with defaults when missing.
func Open(path string) (*Store, error) {
	s := &Store{path: path}
	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := json.Unmarshal(data, &s.cfg); err != nil {
			return nil, fmt.Errorf("parse %s: %w", path, err)
		}
	case errors.Is(err, os.ErrNotExist):
		// Start from defaults below.
	default:
		return nil, fmt.Errorf("read %s: %w", path, err)
	}
	s.cfg.applyDefaults()
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return s, nil
}

// Path returns the configuration file path.
func (s *Store) Path() string { return s.path }

// Snapshot returns a copy of the current configuration.
func (s *Store) Snapshot() Config {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.cfg
	out.Devices = append([]Device(nil), s.cfg.Devices...)
	return out
}

func (s *Store) saveLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("create config dir: %w", err)
	}
	data, err := json.MarshalIndent(s.cfg, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return fmt.Errorf("write config: %w", err)
	}
	if err := os.Rename(tmp, s.path); err != nil {
		// Windows cannot rename over an existing file.
		_ = os.Remove(s.path)
		if err := os.Rename(tmp, s.path); err != nil {
			return fmt.Errorf("replace config: %w", err)
		}
	}
	return nil
}

// Save writes the current configuration.
func (s *Store) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.saveLocked()
}

// PairDevice adds a device with a freshly issued token and returns the token.
// Re-pairing an existing device replaces its token.
func (s *Store) PairDevice(id, name string) (string, error) {
	token, err := NewToken()
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	found := false
	for i := range s.cfg.Devices {
		if s.cfg.Devices[i].ID == id {
			s.cfg.Devices[i].Name = name
			s.cfg.Devices[i].TokenHash = HashToken(token)
			s.cfg.Devices[i].PairedAt = now
			s.cfg.Devices[i].LastSeenAt = time.Time{}
			found = true
			break
		}
	}
	if !found {
		s.cfg.Devices = append(s.cfg.Devices, Device{
			ID:        id,
			Name:      name,
			TokenHash: HashToken(token),
			PairedAt:  now,
		})
	}
	if err := s.saveLocked(); err != nil {
		return "", err
	}
	return token, nil
}

// Authenticate returns the device when the token matches a paired device.
func (s *Store) Authenticate(deviceID, token string) (Device, bool) {
	hash := HashToken(token)
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.cfg.Devices {
		if d.ID != deviceID {
			continue
		}
		if subtleEqual(d.TokenHash, hash) {
			return d, true
		}
	}
	return Device{}, false
}

// MarkSeen records a successful connection.
func (s *Store) MarkSeen(deviceID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.cfg.Devices {
		if s.cfg.Devices[i].ID == deviceID {
			s.cfg.Devices[i].LastSeenAt = time.Now().UTC()
			_ = s.saveLocked()
			return
		}
	}
}

// Unpair removes a device. It returns whether a device was removed.
func (s *Store) Unpair(deviceID string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.cfg.Devices[:0]
	removed := false
	for _, d := range s.cfg.Devices {
		if d.ID == deviceID {
			removed = true
			continue
		}
		kept = append(kept, d)
	}
	s.cfg.Devices = kept
	if !removed {
		return false, nil
	}
	return true, s.saveLocked()
}

// Devices returns the paired devices sorted by pairing time.
func (s *Store) Devices() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := append([]Device(nil), s.cfg.Devices...)
	sort.Slice(out, func(i, j int) bool { return out[i].PairedAt.Before(out[j].PairedAt) })
	return out
}

// SetCommandPrefix updates the wake prefix.
func (s *Store) SetCommandPrefix(prefix string) error {
	prefix = strings.TrimSpace(prefix)
	if prefix == "" {
		return errors.New("prefix must not be empty")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cfg.CommandPrefix = prefix
	return s.saveLocked()
}

func subtleEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
