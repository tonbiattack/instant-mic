package speech

import "testing"

func TestInterpretCommands(t *testing.T) {
	cases := []struct {
		in   string
		kind ResultKind
		key  Key
	}{
		{"コマンド、送信", KindKey, KeyEnter},
		{"コマンド、改行", KindKey, KeyShiftEnter},
		{"コマンド、取り消し", KindKey, KeyCtrlZ},
		{"コマンド、全選択", KindKey, KeyCtrlA},
		{"コマンド送信", KindKey, KeyEnter},
		{"コマンド 送信", KindKey, KeyEnter},
		{"コマンド　送信", KindKey, KeyEnter},
		{"コマンド、送信。", KindKey, KeyEnter},
		{"コマンド、送信！", KindKey, KeyEnter},
		{"コマンド、", KindUnknownCommand, ""},
		{"コマンド", KindUnknownCommand, ""},
		{"コマンド、削除", KindUnknownCommand, ""},
	}
	for _, tc := range cases {
		got := Interpret(tc.in)
		if got.Kind != tc.kind {
			t.Errorf("Interpret(%q).Kind = %v, want %v", tc.in, got.Kind, tc.kind)
		}
		if tc.kind == KindKey && got.Key != tc.key {
			t.Errorf("Interpret(%q).Key = %q, want %q", tc.in, got.Key, tc.key)
		}
	}
}

func TestInterpretText(t *testing.T) {
	cases := []string{
		"送信",
		"これを送信してください",
		"メールを送信",
		"コマンド一覧を教えて",
		"コマンドラインの使い方",
		"コマンドを実行",
		"、送信",
		"改行します",
	}
	for _, in := range cases {
		got := Interpret(in)
		if got.Kind != KindText {
			t.Errorf("Interpret(%q).Kind = %v, want KindText", in, got.Kind)
		}
		if got.Text != in {
			t.Errorf("Interpret(%q).Text = %q, want unchanged", in, got.Text)
		}
	}
}

func TestInterpretEmpty(t *testing.T) {
	for _, in := range []string{"", "   ", "\n\t"} {
		if got := Interpret(in); got.Kind != KindEmpty {
			t.Errorf("Interpret(%q).Kind = %v, want KindEmpty", in, got.Kind)
		}
	}
}

func TestInterpretFullWidth(t *testing.T) {
	// Full-width Latin cannot produce a command, but full-width spaces must not
	// break the prefix/word split.
	got := Interpret("コマンド　、　送信")
	if got.Kind != KindKey || got.Key != KeyEnter {
		t.Fatalf("got %+v, want enter", got)
	}
}

func TestNormalizeFoldsVariants(t *testing.T) {
	pairs := [][2]string{
		{"送信", "送信"},
		{"ｿｳｼﾝ", "ｿｳｼﾝ"},
		{" 送 信 ", "送信"},
		{"送信。", "送信"},
	}
	for _, p := range pairs {
		if got := Normalize(p[0]); got != Normalize(p[1]) {
			t.Errorf("Normalize(%q)=%q != Normalize(%q)=%q", p[0], got, p[1], Normalize(p[1]))
		}
	}
}

func TestIsKnownKey(t *testing.T) {
	for _, k := range []Key{KeyEnter, KeyShiftEnter, KeyCtrlZ, KeyCtrlA} {
		if !IsKnownKey(k) {
			t.Errorf("IsKnownKey(%q) = false", k)
		}
	}
	if IsKnownKey(Key("ctrl+x")) {
		t.Error("unknown key accepted")
	}
}
