package speech

import (
	"sort"
	"strings"
)

// punctuationWords maps a spoken punctuation word to the symbol it types.
type punctuationWord struct {
	spoken string
	symbol string
	// needsPartner means the word is only converted when its pair also appears,
	// so brackets are never left unbalanced. Paired words skip the boundary
	// check (the partner itself proves the intent), while standalone words
	// require a boundary so 「まるで」「てんぷら」 stay intact.
	needsPartner string
}

// punctuationWords is intentionally small and deterministic: only words a user
// can say on purpose. Longer words come first so "かっことじ" is checked before
// "かっこ". It is mirrored by TextShaper.kt in the Android app.
var punctuationWords = []punctuationWord{
	{spoken: "かぎかっことじ", symbol: "」", needsPartner: "かぎかっこ"},
	{spoken: "かぎかっこ", symbol: "「", needsPartner: "かぎかっことじ"},
	{spoken: "かっことじ", symbol: "）", needsPartner: "かっこ"},
	{spoken: "かっこ", symbol: "（", needsPartner: "かっことじ"},
	{spoken: "びっくりまーく", symbol: "！"},
	{spoken: "すらっしゅ", symbol: "/"},
	{spoken: "せみころん", symbol: "；"},
	{spoken: "ころん", symbol: "："},
	{spoken: "はてな", symbol: "？"},
	{spoken: "まる", symbol: "。"},
	{spoken: "てん", symbol: "、"},
	// Coding symbols. These are for dictating code to an assistant; each is a
	// word nobody says by accident in Japanese speech, and like every other
	// standalone word it only converts at a boundary.
	{spoken: "あんだーすこあ", symbol: "_"},
	{spoken: "はいろふん", symbol: "@"},
	{spoken: "ばっくくぉーと", symbol: "`"},
	{spoken: "だぶるくぉーと", symbol: "\""},
	{spoken: "ぱーせんと", symbol: "%"},
	{spoken: "あんど", symbol: "&"},
	{spoken: "ぱいぷ", symbol: "|"},
	{spoken: "いこーる", symbol: "="},
	{spoken: "ぷらす", symbol: "+"},
	{spoken: "まいなす", symbol: "-"},
	{spoken: "あすたりすく", symbol: "*"},
	{spoken: "どる", symbol: "$"},
	{spoken: "しゃーぷ", symbol: "#"},
	{spoken: "はっしゅ", symbol: "#"},
	{spoken: "ちるだ", symbol: "~"},
	{spoken: "かっこまる", symbol: "()"},
	{spoken: "かっこはな", symbol: "[]"},
	{spoken: "かっこなみ", symbol: "{}"},
}

// init sorts the table longest-first so a shorter word can never win at a
// position where a longer one starts ("かっこまる" before "かっこ"). Sorting
// once here means a new entry cannot silently break the longest-match rule by
// being appended in the wrong place.
func init() {
	sort.SliceStable(punctuationWords, func(i, j int) bool {
		return len([]rune(punctuationWords[i].spoken)) > len([]rune(punctuationWords[j].spoken))
	})
}

// ShapeText converts spoken punctuation at a safe boundary.
//
// Standalone words convert only when the word ends the text or is followed by
// whitespace or existing punctuation, which keeps 「まるで違う」 and
// 「てんぷら」 intact while 「こんにちはまる」 becomes 「こんにちは。」. Paired
// brackets convert only when both halves are present. When the symbol is the
// same as the punctuation that already follows, the duplicate is consumed so
// 「てん、」 does not become 「、、」.
//
// Only literal text reaches this function; commands are resolved before shaping.
func ShapeText(text string) string {
	if text == "" {
		return text
	}
	runes := []rune(text)
	var out strings.Builder
	out.Grow(len(text))

	for i := 0; i < len(runes); {
		// The table is ordered longest-first, so the first matching word is the
		// longest one. A shorter word must not be considered at the same
		// position, otherwise "かっことじ" would fall back to "かっこ".
		var match *punctuationWord
		for idx := range punctuationWords {
			word := &punctuationWords[idx]
			wordRunes := []rune(word.spoken)
			if i+len(wordRunes) > len(runes) {
				continue
			}
			if string(runes[i:i+len(wordRunes)]) == word.spoken {
				match = word
				break
			}
		}
		if match == nil {
			out.WriteRune(runes[i])
			i++
			continue
		}

		wordRunes := []rune(match.spoken)
		usable := true
		if match.needsPartner != "" {
			usable = partnerPresent(runes, match.needsPartner)
		} else if !boundaryAfter(runes, i+len(wordRunes)) {
			usable = false
		}
		if !usable {
			out.WriteRune(runes[i])
			i++
			continue
		}

		out.WriteString(match.symbol)
		i += len(wordRunes)
		// Do not emit "、、" or "。。" when the recognizer already punctuated.
		if i < len(runes) && string(runes[i]) == match.symbol {
			i++
		}
	}
	return out.String()
}

// boundaryAfter reports whether position i may end a standalone punctuation
// word: end of text, whitespace, or existing punctuation.
func boundaryAfter(runes []rune, i int) bool {
	if i >= len(runes) {
		return true
	}
	switch runes[i] {
	case ' ', '\t', '\n', '\r':
		return true
	}
	return isPunctuationRune(runes[i])
}

func isPunctuationRune(r rune) bool {
	switch r {
	case '、', '。', '，', '．', '！', '？', '!', '?', '：', '；', ',', '.', ':', ';',
		'（', '）', '「', '」', '『', '』', '(', ')':
		return true
	}
	return false
}

// partnerPresent reports whether the partner word appears as a separate word.
// A plain substring check would match "かっこ" inside "かっことじ", leaving an
// unbalanced 「 without its closing bracket.
// partnerPresent reports whether the partner word appears as a whole word.
//
// A plain substring check would match "かっこ" inside "かっことじ", leaving an
// unbalanced 「 without its closing bracket, so a match that is only the prefix
// of a longer word is skipped.
func partnerPresent(runes []rune, partner string) bool {
	partnerRunes := []rune(partner)
	for i := 0; i+len(partnerRunes) <= len(runes); i++ {
		if string(runes[i:i+len(partnerRunes)]) != partner {
			continue
		}
		if startsWithLongerWord(runes[i:], len(partnerRunes)) {
			continue
		}
		return true
	}
	return false
}

// startsWithLongerWord reports whether rest begins with a punctuation word that
// is longer than length runes.
func startsWithLongerWord(rest []rune, length int) bool {
	text := string(rest)
	for _, word := range punctuationWords {
		if len([]rune(word.spoken)) <= length {
			continue
		}
		if strings.HasPrefix(text, word.spoken) {
			return true
		}
	}
	return false
}
