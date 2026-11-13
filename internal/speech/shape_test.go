package speech

import "testing"

func TestShapeText(t *testing.T) {
	cases := map[string]string{
		"こんにちはまる":       "こんにちは。",
		"これとこれてん、お願い":   "これとこれ、お願い",
		"そうですねまるまる":     "そうですねまる。",
		"本当にはてな？":       "本当に？",
		"おわり":           "おわり",
		"まるで違う":         "まるで違う",
		"てんぷらが食べたい":     "てんぷらが食べたい",
		"これはまる、それからてん。": "これは。、それから、。",
		"かっこテストかっことじ":   "（テスト）",
		"びっくりまーく":       "！",
		"ぱすはすらっしゅ、です":   "ぱすは/、です",
	}
	for in, want := range cases {
		if got := ShapeText(in); got != want {
			t.Errorf("ShapeText(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestShapeTextDoesNotBreakWords(t *testing.T) {
	// A punctuation word followed immediately by more text is part of a word.
	for _, in := range []string{"まるい", "てんかい", "ころんで", "はてなの", "まる"} {
		got := ShapeText(in)
		if in != "まる" && got != in {
			t.Errorf("ShapeText(%q) = %q, want unchanged", in, got)
		}
	}
	// A lone word followed by nothing is still a symbol.
	if got := ShapeText("まる"); got != "。" {
		t.Errorf("ShapeText(\"まる\") = %q, want 。", got)
	}
}

func TestShapeTextCodingSymbols(t *testing.T) {
	// Coding symbols only convert as whole words at a boundary.
	cases := map[string]string{
		"あんだーすこあ": "_",
		"はいろふん":   "@",
		"ぱいぷ":     "|",
		"いこーる":    "=",
		"しゃーぷ":    "#",
		"かっこはな":   "[]",
		"かっこなみ":   "{}",
		"かっこまる":   "()",
	}
	for in, want := range cases {
		if got := ShapeText(in); got != want {
			t.Errorf("ShapeText(%q) = %q, want %q", in, got, want)
		}
	}
	// Mid-word occurrences must be left alone.
	for _, in := range []string{"ぷらすの", "あんどで", "どるい"} {
		if got := ShapeText(in); got != in {
			t.Errorf("ShapeText(%q) = %q, want unchanged", in, got)
		}
	}
}

func TestShapeTextNeedsBalancedBrackets(t *testing.T) {	// Unpaired bracket words stay literal so the text never becomes unbalanced.
	if got := ShapeText("かっこだけ"); got != "かっこだけ" {
		t.Errorf("unpaired bracket shaped: %q", got)
	}
	if got := ShapeText("かっことじだけ"); got != "かっことじだけ" {
		t.Errorf("unpaired closing bracket shaped: %q", got)
	}
}

func TestInterpretShapesLiteralText(t *testing.T) {
	got := Interpret("こんにちはまる")
	if got.Kind != KindText {
		t.Fatalf("kind = %v", got.Kind)
	}
	if got.Text != "こんにちは。" {
		t.Fatalf("text = %q, want こんにちは。", got.Text)
	}
	// Commands are resolved before shaping, so a command word is untouched.
	if got := Interpret("コマンド、送信"); got.Kind != KindKey {
		t.Fatalf("command shaped away: %+v", got)
	}
	// The wake word itself is never written out as punctuation.
	got = Interpret("コマンド一覧を教えて")
	if got.Kind != KindText || got.Text != "コマンド一覧を教えて" {
		t.Fatalf("got %+v", got)
	}
}
