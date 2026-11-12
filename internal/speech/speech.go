// Package speech interprets finalized Japanese speech transcripts.
//
// The rules here are mirrored by the Android client
// (android/app/src/main/java/com/tonbiattack/instantmic/domain/CommandParser.kt).
// Keep both implementations in sync. The receiver applies the rules again so a
// command can never be executed as a side effect of a partial match.
package speech

import (
	"strings"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

// CommandPrefix is the default wake prefix for voice commands.
const CommandPrefix = "コマンド"

// MaxTextRunes bounds the amount of text accepted from a client.
const MaxTextRunes = 4000

// Key identifies a key action that can be sent to Windows.
type Key string

const (
	KeyEnter      Key = "enter"
	KeyShiftEnter Key = "shift+enter"
	KeyCtrlZ      Key = "ctrl+z"
	KeyCtrlA      Key = "ctrl+a"
)

// IsKnownKey reports whether k is a supported key action.
func IsKnownKey(k Key) bool {
	switch k {
	case KeyEnter, KeyShiftEnter, KeyCtrlZ, KeyCtrlA:
		return true
	}
	return false
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
	Key  Key
	// Raw is the normalized command phrase for diagnostics.
	Raw string
}

var commandKeys = map[string]Key{
	"送信":   KeyEnter,
	"改行":   KeyShiftEnter,
	"取り消し": KeyCtrlZ,
	"取消":   KeyCtrlZ,
	"全選択":  KeyCtrlA,
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
	key, known := commandKeys[normRest]
	if !known {
		if !separated && normRest != "" {
			// "コマンド一覧…" — the prefix is part of a normal word.
			return Result{Kind: KindText, Text: trimmed}
		}
		// Bare wake word or unsupported command: never type or press.
		return Result{Kind: KindUnknownCommand, Raw: CommandPrefix + normRest}
	}
	return Result{Kind: KindKey, Key: key, Raw: CommandPrefix + normRest}
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
