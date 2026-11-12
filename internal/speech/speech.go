// Package speech interprets finalized Japanese speech transcripts.
//
// The rules here are mirrored by the Android client
// (android/app/src/main/java/com/tonbiattack/instantmic/domain/CommandParser.kt).
// Keep both implementations in sync. The receiver applies the rules again so a
// command can never be executed as a side effect of a partial match.
package speech

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

// CommandPrefix is the default wake prefix for voice commands.
const CommandPrefix = "コマンド"

// MaxTextRunes bounds the amount of text accepted from a client.
const MaxTextRunes = 4000

// MaxSequenceSteps bounds how many key chords one command may contain. It keeps
// a malformed or malicious key message from becoming a long macro.
const MaxSequenceSteps = 8

// MaxRepeat bounds the spoken repeat count ("三回削除").
const MaxRepeat = 5

// Modifier is a keyboard modifier held for one chord.
type Modifier string

const (
	ModCtrl  Modifier = "ctrl"
	ModShift Modifier = "shift"
	ModAlt   Modifier = "alt"
	ModWin   Modifier = "win"
)

// modifierOrder is the canonical order used in wire strings and labels.
var modifierOrder = []Modifier{ModCtrl, ModShift, ModAlt, ModWin}

// Combo is one key chord: modifiers held while a single key is pressed.
type Combo struct {
	Mods []Modifier
	Key  string
}

// String returns the canonical wire form of the chord, e.g. "ctrl+shift+k".
func (c Combo) String() string {
	parts := make([]string, 0, len(c.Mods)+1)
	for _, m := range c.Mods {
		parts = append(parts, string(m))
	}
	parts = append(parts, c.Key)
	return strings.Join(parts, "+")
}

// Label returns a human readable form, e.g. "Ctrl+Shift+K".
func (c Combo) Label() string {
	parts := make([]string, 0, len(c.Mods)+1)
	for _, m := range c.Mods {
		parts = append(parts, modifierLabel(m))
	}
	parts = append(parts, keyLabel(c.Key))
	return strings.Join(parts, "+")
}

// Sequence is an ordered list of chords. Several chords make a small macro,
// e.g. "home>shift+end>delete" for deleting the current line.
type Sequence []Combo

// String returns the canonical wire form, e.g. "home>shift+end>delete".
func (s Sequence) String() string {
	parts := make([]string, 0, len(s))
	for _, c := range s {
		parts = append(parts, c.String())
	}
	return strings.Join(parts, ">")
}

// Label returns a human readable form, e.g. "Home → Shift+End → Delete".
func (s Sequence) Label() string {
	parts := make([]string, 0, len(s))
	for _, c := range s {
		parts = append(parts, c.Label())
	}
	return strings.Join(parts, " → ")
}

// StepCount returns the number of chords.
func (s Sequence) StepCount() int { return len(s) }

var namedKeys = map[string]struct{}{
	"enter": {}, "tab": {}, "escape": {}, "backspace": {}, "delete": {},
	"insert": {}, "space": {}, "up": {}, "down": {}, "left": {}, "right": {},
	"home": {}, "end": {}, "pageup": {}, "pagedown": {},
}

var keyAliases = map[string]string{
	"return":     "enter",
	"esc":        "escape",
	"del":        "delete",
	"ins":        "insert",
	"spacebar":   "space",
	"pgup":       "pageup",
	"pgdn":       "pagedown",
	"page_up":    "pageup",
	"page_down":  "pagedown",
	"arrowup":    "up",
	"arrowdown":  "down",
	"arrowleft":  "left",
	"arrowright": "right",
}

var modifierAliases = map[string]Modifier{
	"ctrl":    ModCtrl,
	"control": ModCtrl,
	"shift":   ModShift,
	"alt":     ModAlt,
	"win":     ModWin,
	"windows": ModWin,
	"super":   ModWin,
	"meta":    ModWin,
}

// IsKnownKeyName reports whether name (already lowercased) is a supported key.
func IsKnownKeyName(name string) bool {
	if _, ok := namedKeys[name]; ok {
		return true
	}
	if len(name) == 1 {
		r := rune(name[0])
		return (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9')
	}
	if strings.HasPrefix(name, "f") && len(name) <= 3 {
		n, err := strconv.Atoi(name[1:])
		return err == nil && n >= 1 && n <= 24
	}
	return false
}

// ParseSequence validates and canonicalises a key sequence such as
// "backspace", "ctrl+backspace" or "home>shift+end>delete".
//
// Only keys on the allowlist are accepted. The receiver uses this before it
// injects anything, so the Android client cannot ask for arbitrary keys.
func ParseSequence(value string) (Sequence, error) {
	text := strings.TrimSpace(value)
	if text == "" {
		return nil, errors.New("empty key sequence")
	}
	if len(text) > 256 {
		return nil, errors.New("key sequence is too long")
	}
	parts := strings.Split(text, ">")
	if len(parts) > MaxSequenceSteps {
		return nil, fmt.Errorf("key sequence has more than %d steps", MaxSequenceSteps)
	}
	seq := make(Sequence, 0, len(parts))
	for _, part := range parts {
		combo, err := parseCombo(part)
		if err != nil {
			return nil, err
		}
		seq = append(seq, combo)
	}
	return seq, nil
}

func parseCombo(value string) (Combo, error) {
	raw := strings.Split(value, "+")
	seen := map[Modifier]bool{}
	var mods []Modifier
	var key string
	for _, token := range raw {
		token = strings.ToLower(strings.TrimSpace(token))
		if token == "" {
			return Combo{}, fmt.Errorf("invalid key chord %q", value)
		}
		if m, ok := modifierAliases[token]; ok {
			if seen[m] {
				return Combo{}, fmt.Errorf("duplicate modifier %q in %q", token, value)
			}
			seen[m] = true
			mods = append(mods, m)
			continue
		}
		if alias, ok := keyAliases[token]; ok {
			token = alias
		}
		if !IsKnownKeyName(token) {
			return Combo{}, fmt.Errorf("unsupported key %q", token)
		}
		if key != "" {
			return Combo{}, fmt.Errorf("key chord %q has more than one key", value)
		}
		key = token
	}
	if key == "" {
		return Combo{}, fmt.Errorf("key chord %q has no key", value)
	}
	mods = sortModifiers(mods)
	// Ctrl+Alt+Delete opens the Windows security screen and cannot be injected
	// safely, so it is refused even though each part is allowlisted.
	if key == "delete" && len(mods) == 2 && mods[0] == ModCtrl && mods[1] == ModAlt {
		return Combo{}, errors.New("ctrl+alt+delete is not allowed")
	}
	return Combo{Mods: mods, Key: key}, nil
}

func sortModifiers(mods []Modifier) []Modifier {
	if len(mods) == 0 {
		return nil
	}
	out := make([]Modifier, 0, len(mods))
	for _, want := range modifierOrder {
		for _, m := range mods {
			if m == want {
				out = append(out, m)
			}
		}
	}
	return out
}

func modifierLabel(m Modifier) string {
	switch m {
	case ModCtrl:
		return "Ctrl"
	case ModShift:
		return "Shift"
	case ModAlt:
		return "Alt"
	case ModWin:
		return "Win"
	}
	return string(m)
}

func keyLabel(key string) string {
	switch key {
	case "pageup":
		return "PageUp"
	case "pagedown":
		return "PageDown"
	}
	if len(key) == 1 {
		return strings.ToUpper(key)
	}
	if len(key) > 1 && key[0] == 'f' && IsKnownKeyName(key) {
		return strings.ToUpper(key)
	}
	if key == "" {
		return key
	}
	return strings.ToUpper(key[:1]) + key[1:]
}

func mustSeq(value string) Sequence {
	seq, err := ParseSequence(value)
	if err != nil {
		panic("speech: invalid built-in key sequence " + value + ": " + err.Error())
	}
	return seq
}

// ResultKind is the outcome of interpreting a transcript.
type ResultKind int

const (
	// KindText means the transcript should be typed literally.
	KindText ResultKind = iota
	// KindKey means a fixed key action should be performed.
	KindKey
	// KindUnknownCommand means the wake prefix was used with an unsupported
	// command word. Nothing is typed and no key is pressed; the client shows an
	// error so the user can retry.
	KindUnknownCommand
	// KindEmpty means there is nothing to do.
	KindEmpty
)

// Result is the interpreted form of a transcript.
type Result struct {
	Kind ResultKind
	Text string
	Key  Sequence
	// Label is the human readable key combination for the UI.
	Label string
	// Count is how many times the sequence should run (1 unless repeated).
	Count int
	// Raw is the normalized command phrase for diagnostics.
	Raw string
}

type command struct {
	phrase      string
	aliases     []string
	sequence    Sequence
	label       string
	allowRepeat bool
}

// builtinCommands is the single source of truth for standard phrases. Keep the
// Android CommandParser table in sync with this list.
var builtinCommands = []command{
	{phrase: "送信", sequence: mustSeq("enter"), label: "Enter"},
	{phrase: "改行", aliases: []string{"開業"}, sequence: mustSeq("shift+enter"), label: "Shift+Enter"},
	{phrase: "取り消し", aliases: []string{"取消", "とりけし"}, sequence: mustSeq("ctrl+z"), label: "Ctrl+Z"},
	{phrase: "全選択", sequence: mustSeq("ctrl+a"), label: "Ctrl+A"},
	{phrase: "削除", aliases: []string{"バックスペース"}, sequence: mustSeq("backspace"), label: "Backspace", allowRepeat: true},
	{phrase: "前の単語削除", aliases: []string{"単語削除"}, sequence: mustSeq("ctrl+backspace"), label: "Ctrl+Backspace", allowRepeat: true},
	{phrase: "行削除", sequence: mustSeq("home>shift+end>delete"), label: "行削除 (Home → Shift+End → Delete)"},
	{phrase: "デリート", sequence: mustSeq("delete"), label: "Delete", allowRepeat: true},
	{phrase: "上", sequence: mustSeq("up"), label: "↑", allowRepeat: true},
	{phrase: "下", sequence: mustSeq("down"), label: "↓", allowRepeat: true},
	{phrase: "左", sequence: mustSeq("left"), label: "←", allowRepeat: true},
	{phrase: "右", sequence: mustSeq("right"), label: "→", allowRepeat: true},
	{phrase: "コピー", sequence: mustSeq("ctrl+c"), label: "Ctrl+C"},
	{phrase: "貼り付け", sequence: mustSeq("ctrl+v"), label: "Ctrl+V"},
	{phrase: "切り取り", sequence: mustSeq("ctrl+x"), label: "Ctrl+X"},
	{phrase: "やり直し", sequence: mustSeq("ctrl+y"), label: "Ctrl+Y"},
	{phrase: "タブ切り替え", sequence: mustSeq("ctrl+tab"), label: "Ctrl+Tab"},
}

var (
	// commandLookup maps a normalized phrase or alias to its command.
	commandLookup = map[string]*command{}
	// BuiltinPhrases lists every normalized built-in phrase, for conflict
	// checks when a custom command is registered.
	BuiltinPhrases = map[string]struct{}{}
)

func init() {
	for i := range builtinCommands {
		cmd := &builtinCommands[i]
		commandLookup[Normalize(cmd.phrase)] = cmd
		BuiltinPhrases[Normalize(cmd.phrase)] = struct{}{}
		for _, alias := range cmd.aliases {
			commandLookup[Normalize(alias)] = cmd
			BuiltinPhrases[Normalize(alias)] = struct{}{}
		}
	}
}

// Normalize folds width, whitespace and punctuation so that variants produced
// by speech recognition compare equal. It is used for command matching only;
// literal text is sent unchanged except for trimming.
func Normalize(s string) string {
	s = norm.NFKC.String(s)
	var b strings.Builder
	for _, r := range s {
		if unicode.IsSpace(r) || unicode.IsPunct(r) || unicode.IsSymbol(r) {
			continue
		}
		b.WriteRune(unicode.ToLower(r))
	}
	return b.String()
}

// Interpret decides whether a finalized transcript is a command or literal text.
//
// A command needs the wake prefix followed by either a separator
// ("コマンド、送信") or a standalone command word ("コマンド送信", where speech
// recognition dropped the punctuation). Anything else that merely starts with
// the prefix, such as "コマンド一覧を教えて", stays literal text. The command word
// is never searched for inside a normal sentence: "この文章を送信" stays text.
func Interpret(transcript string) Result {
	trimmed := strings.TrimSpace(transcript)
	if trimmed == "" {
		return Result{Kind: KindEmpty}
	}

	// NFKC folds full-width ASCII and spacing. Punctuation is still intact here,
	// so we can tell a spoken separator from a command word glued to the prefix.
	light := strings.TrimSpace(norm.NFKC.String(trimmed))
	if !strings.HasPrefix(light, CommandPrefix) {
		return Result{Kind: KindText, Text: trimmed}
	}

	after := light[len(CommandPrefix):]
	separated := false
	for {
		r := []rune(after)
		if len(r) == 0 || !isSeparator(r[0]) {
			break
		}
		separated = true
		after = string(r[1:])
	}

	normRest := Normalize(after)
	if cmd := commandLookup[normRest]; cmd != nil {
		return Result{
			Kind:  KindKey,
			Key:   cmd.sequence,
			Label: cmd.label,
			Count: 1,
			Raw:   CommandPrefix + normRest,
		}
	}

	// Repeated commands: "3回削除", "削除3回", "三回削除".
	if base, count, ok := splitRepeat(normRest); ok {
		if cmd := commandLookup[base]; cmd != nil && cmd.allowRepeat {
			if count > MaxRepeat {
				count = MaxRepeat
			}
			if count < 1 {
				count = 1
			}
			return Result{
				Kind:  KindKey,
				Key:   cmd.sequence,
				Label: fmt.Sprintf("%s ×%d", cmd.label, count),
				Count: count,
				Raw:   CommandPrefix + normRest,
			}
		}
	}

	if !separated && normRest != "" {
		// "コマンド一覧…" — the prefix is part of a normal word.
		return Result{Kind: KindText, Text: trimmed}
	}
	// Bare wake word or unsupported command: never type or press.
	return Result{Kind: KindUnknownCommand, Raw: CommandPrefix + normRest}
}

// splitRepeat separates an optional spoken repeat count from a command word.
func splitRepeat(normRest string) (string, int, bool) {
	if normRest == "" {
		return "", 0, false
	}
	// Prefix form: 3回削除
	if i := strings.Index(normRest, "回"); i > 0 && i < len(normRest) {
		if n, ok := parseCount(normRest[:i]); ok {
			return normRest[i+len("回"):], n, true
		}
	}
	// Suffix form: 削除3回 (the last 回 is the marker)
	if strings.HasSuffix(normRest, "回") {
		body := normRest[:len(normRest)-len("回")]
		start := len(body)
		for start > 0 {
			r := rune(body[start-1])
			if unicode.IsDigit(r) || isKanjiDigit(r) {
				start--
				continue
			}
			break
		}
		if start < len(body) {
			if n, ok := parseCount(body[start:]); ok {
				return body[:start], n, true
			}
		}
	}
	return "", 0, false
}

func parseCount(s string) (int, bool) {
	if s == "" {
		return 0, false
	}
	if n, err := strconv.Atoi(s); err == nil {
		return n, n > 0
	}
	// Simple Japanese numerals up to 99.
	digits := map[rune]int{'一': 1, '二': 2, '三': 3, '四': 4, '五': 5,
		'六': 6, '七': 7, '八': 8, '九': 9}
	total := 0
	ten := false
	for _, r := range s {
		if r == '十' {
			if ten {
				return 0, false
			}
			ten = true
			if total == 0 {
				total = 1
			}
			total *= 10
			continue
		}
		if n, ok := digits[r]; ok {
			total += n
			continue
		}
		return 0, false
	}
	return total, total > 0
}

func isKanjiDigit(r rune) bool {
	switch r {
	case '一', '二', '三', '四', '五', '六', '七', '八', '九', '十':
		return true
	}
	return false
}

// isSeparator reports whether r may separate the wake prefix from the command
// word. Speech recognition often inserts 、 or a space.
func isSeparator(r rune) bool {
	switch r {
	case '、', ',', '，', '。', '．', '!', '！', '?', '？', ':', '：', '「', '」', '『', '』', '・':
		return true
	}
	return unicode.IsSpace(r)
}
