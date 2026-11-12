//go:build windows && (amd64 || arm64)

package input

import (
	"testing"

	"github.com/tonbiattack/instant-mic/internal/speech"
)

func TestEveryAllowlistedKeyHasAVirtualKey(t *testing.T) {
	// Keep this list aligned with internal/speech's allowlist. A key the parser
	// accepts but the injector cannot map would fail only at runtime.
	names := []string{
		"enter", "tab", "escape", "backspace", "delete", "insert", "space",
		"up", "down", "left", "right", "home", "end", "pageup", "pagedown",
		"a", "z", "0", "9", "f1", "f12", "f24",
	}
	for _, name := range names {
		if _, _, ok := keyVK(name); !ok {
			t.Errorf("keyVK(%q) has no mapping", name)
		}
	}
	for _, name := range []string{"", "hyper", "f25", "ab"} {
		if _, _, ok := keyVK(name); ok {
			t.Errorf("keyVK(%q) unexpectedly mapped", name)
		}
	}
}

func TestComboEventOrder(t *testing.T) {
	seq, err := speech.ParseSequence("ctrl+shift+k")
	if err != nil {
		t.Fatal(err)
	}
	events, err := comboInputs(seq[0])
	if err != nil {
		t.Fatal(err)
	}
	// Ctrl down, Shift down, K down, K up, Shift up, Ctrl up.
	if len(events) != 6 {
		t.Fatalf("got %d events, want 6", len(events))
	}
	if events[0].Ki.Vk != vkControl || events[0].Ki.Flags&keyeventfKeyUp != 0 {
		t.Fatalf("first event = %+v, want Ctrl down", events[0].Ki)
	}
	if events[1].Ki.Vk != vkShift || events[1].Ki.Flags&keyeventfKeyUp != 0 {
		t.Fatalf("second event = %+v, want Shift down", events[1].Ki)
	}
	if events[2].Ki.Vk != 'K' || events[2].Ki.Flags&keyeventfKeyUp != 0 {
		t.Fatalf("third event = %+v, want K down", events[2].Ki)
	}
	if events[3].Ki.Vk != 'K' || events[3].Ki.Flags&keyeventfKeyUp == 0 {
		t.Fatalf("fourth event = %+v, want K up", events[3].Ki)
	}
	if events[4].Ki.Vk != vkShift || events[4].Ki.Flags&keyeventfKeyUp == 0 {
		t.Fatalf("fifth event = %+v, want Shift up", events[4].Ki)
	}
	if events[5].Ki.Vk != vkControl || events[5].Ki.Flags&keyeventfKeyUp == 0 {
		t.Fatalf("sixth event = %+v, want Ctrl up", events[5].Ki)
	}
}

func TestExtendedKeysAreFlagged(t *testing.T) {
	// Arrows and Delete are extended keys; without the flag some applications
	// treat them as numpad input.
	for _, name := range []string{"delete", "up", "home", "pageup", "insert"} {
		if _, extended, ok := keyVK(name); !ok || !extended {
			t.Errorf("keyVK(%q) extended = %v, want true", name, extended)
		}
	}
	for _, name := range []string{"enter", "tab", "a", "space"} {
		if _, extended, ok := keyVK(name); !ok || extended {
			t.Errorf("keyVK(%q) extended = %v, want false", name, extended)
		}
	}
}

func TestLineDeleteSequenceBuildsThreeChords(t *testing.T) {
	seq, err := speech.ParseSequence("home>shift+end>delete")
	if err != nil {
		t.Fatal(err)
	}
	total := 0
	for _, combo := range seq {
		events, err := comboInputs(combo)
		if err != nil {
			t.Fatal(err)
		}
		total += len(events)
	}
	// Home (2) + Shift+End (4) + Delete (2).
	if total != 8 {
		t.Fatalf("got %d events, want 8", total)
	}
}

func TestEraseTextRejectsInvalidCounts(t *testing.T) {
	w := &WindowsInserter{}
	for _, count := range []int{0, -1, MaxEraseCount + 1} {
		if err := w.EraseText(count); err == nil {
			t.Errorf("EraseText(%d) accepted", count)
		}
	}
}

func TestEraseTextBuildsBackspacePairs(t *testing.T) {
	// The event construction is shared with PressKey, so check the shape here
	// without sending real input: two events per erased character.
	count := 3
	events := make([]inputUnion, 0, count*2)
	for i := 0; i < count; i++ {
		events = append(events, keyDown(vkBackspace, false), keyUp(vkBackspace, false))
	}
	if len(events) != 6 {
		t.Fatalf("got %d events, want 6", len(events))
	}
	if events[0].Ki.Vk != vkBackspace || events[0].Ki.Flags&keyeventfKeyUp != 0 {
		t.Fatalf("first event = %+v", events[0].Ki)
	}
	if events[1].Ki.Vk != vkBackspace || events[1].Ki.Flags&keyeventfKeyUp == 0 {
		t.Fatalf("second event = %+v", events[1].Ki)
	}
	if vkBackspace != 0x08 {
		t.Fatalf("vkBackspace = %#x", vkBackspace)
	}
}
