package speech

import "testing"

func TestInterpretCommands(t *testing.T) {
	cases := []struct {
		in    string
		kind  ResultKind
		key   string
		count int
	}{
		{"コマンド、送信", KindKey, "enter", 1},
		{"コマンド、改行", KindKey, "shift+enter", 1},
		{"コマンド、取り消し", KindKey, "ctrl+z", 1},
		{"コマンド、全選択", KindKey, "ctrl+a", 1},
		{"コマンド送信", KindKey, "enter", 1},
		{"コマンド 送信", KindKey, "enter", 1},
		{"コマンド　送信", KindKey, "enter", 1},
		{"コマンド、送信。", KindKey, "enter", 1},
		{"コマンド、送信！", KindKey, "enter", 1},
		{"コマンド、削除", KindKey, "backspace", 1},
		{"コマンド、バックスペース", KindKey, "backspace", 1},
		{"コマンド、バック スペース", KindKey, "backspace", 1},
		{"コマンド、前の単語削除", KindKey, "ctrl+backspace", 1},
		{"コマンド、行削除", KindKey, "home>shift+end>delete", 1},
		{"コマンド、デリート", KindKey, "delete", 1},
		{"コマンド、上", KindKey, "up", 1},
		{"コマンド、下", KindKey, "down", 1},
		{"コマンド、左", KindKey, "left", 1},
		{"コマンド、右", KindKey, "right", 1},
		{"コマンド、コピー", KindKey, "ctrl+c", 1},
		{"コマンド、貼り付け", KindKey, "ctrl+v", 1},
		{"コマンド、切り取り", KindKey, "ctrl+x", 1},
		{"コマンド、やり直し", KindKey, "ctrl+y", 1},
		{"コマンド、タブ切り替え", KindKey, "ctrl+tab", 1},
		{"コマンド、3回削除", KindKey, "backspace", 3},
		{"コマンド、削除3回", KindKey, "backspace", 3},
		{"コマンド、三回削除", KindKey, "backspace", 3},
		{"コマンド、10回上", KindKey, "up", 5},
		{"コマンド、", KindUnknownCommand, "", 0},
		{"コマンド", KindUnknownCommand, "", 0},
		{"コマンド、存在しない", KindUnknownCommand, "", 0},
	}
	for _, tc := range cases {
		got := Interpret(tc.in)
		if got.Kind != tc.kind {
			t.Errorf("Interpret(%q).Kind = %v, want %v", tc.in, got.Kind, tc.kind)
			continue
		}
		if tc.kind != KindKey {
			continue
		}
		if got.Key.String() != tc.key {
			t.Errorf("Interpret(%q).Key = %q, want %q", tc.in, got.Key.String(), tc.key)
		}
		if got.Count != tc.count {
			t.Errorf("Interpret(%q).Count = %d, want %d", tc.in, got.Count, tc.count)
		}
	}
}

func TestMisrecognitionAliases(t *testing.T) {
	// The observed misrecognition for 改行 is 開業; only the whole phrase may
	// resolve, never a sentence that merely contains it.
	if got := Interpret("コマンド、開業"); got.Kind != KindKey || got.Key.String() != "shift+enter" {
		t.Fatalf("コマンド、開業 -> %+v", got)
	}
	if got := Interpret("コマンド 開業"); got.Kind != KindKey || got.Key.String() != "shift+enter" {
		t.Fatalf("コマンド 開業 -> %+v", got)
	}
	for _, in := range []string{"開業しました", "コマンド開業について説明して", "コマンド開業について教えて"} {
		if got := Interpret(in); got.Kind != KindText {
			t.Errorf("Interpret(%q).Kind = %v, want KindText", in, got.Kind)
		}
	}
}

func TestDestructiveCommandsNeedExactMatch(t *testing.T) {
	for _, in := range []string{"コマンド削除について", "コマンド、削除する方法", "削除", "コマンド、行削除のやり方"} {
		if got := Interpret(in); got.Kind == KindKey {
			t.Errorf("Interpret(%q) executed %v", in, got.Key)
		}
	}
}

func TestRepeatOnlyForSafeCommands(t *testing.T) {
	for _, in := range []string{"コマンド、3回送信", "コマンド、2回行削除", "コマンド、2回コピー"} {
		got := Interpret(in)
		if got.Kind == KindKey {
			t.Errorf("Interpret(%q) repeated a command that must not repeat: %v", in, got.Key)
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
		"削除しました",
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
	if got.Kind != KindKey || got.Key.String() != "enter" {
		t.Fatalf("got %+v, want enter", got)
	}
}

func TestKatakanaKeyAliases(t *testing.T) {
	cases := map[string]string{
		"コマンド、エンター":    "enter",
		"コマンドエンター":     "enter",
		"コマンド エンター":    "enter",
		"コマンド、えんたー":    "enter",
		"コマンド、シフトエンター": "shift+enter",
		"コマンド、バックスペース": "backspace",
		"コマンド、デリート":    "delete",
		"コマンド、アップ":     "up",
		"コマンド、ダウン":     "down",
		"コマンド、レフト":     "left",
		"コマンド、ライト":     "right",
		"コマンド、ペースト":    "ctrl+v",
	}
	for in, want := range cases {
		got := Interpret(in)
		if got.Kind != KindKey || got.Key.String() != want {
			t.Errorf("Interpret(%q) = %+v, want %s", in, got, want)
		}
	}
}

func TestKatakanaAliasesDoNotLeakIntoText(t *testing.T) {
	// A word that merely contains a key name must stay text.
	for _, in := range []string{
		"エンターキーを押す",
		"デリートしました",
		"コマンドエンターについて説明して",
	} {
		if got := Interpret(in); got.Kind != KindText {
			t.Errorf("Interpret(%q).Kind = %v, want KindText", in, got.Kind)
		}
	}
}

func TestInterpretErase(t *testing.T) {
	for _, in := range []string{
		"コマンド、言い直し",
		"コマンド言い直し",
		"コマンド 言い直し",
		"コマンド、言直し",
		"コマンド、言い直し。",
	} {
		if got := Interpret(in); got.Kind != KindErase {
			t.Errorf("Interpret(%q).Kind = %v, want KindErase", in, got.Kind)
		}
	}
	// The wake word inside a normal sentence must stay text.
	for _, in := range []string{
		"言い直しします",
		"コマンド言い直しについて説明して",
		"もう一度言い直し",
	} {
		if got := Interpret(in); got.Kind != KindText {
			t.Errorf("Interpret(%q).Kind = %v, want KindText", in, got.Kind)
		}
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

func TestParseSequence(t *testing.T) {
	good := map[string]string{
		"enter":                 "enter",
		"SHIFT+ENTER":           "shift+enter",
		" ctrl + backspace ":    "ctrl+backspace",
		"home>shift+end>delete": "home>shift+end>delete",
		"esc":                   "escape",
		"ctrl+shift+k":          "ctrl+shift+k",
		"f5":                    "f5",
		"win+up":                "win+up",
		"pgdn":                  "pagedown",
		"a":                     "a",
	}
	for in, want := range good {
		seq, err := ParseSequence(in)
		if err != nil {
			t.Errorf("ParseSequence(%q): %v", in, err)
			continue
		}
		if got := seq.String(); got != want {
			t.Errorf("ParseSequence(%q) = %q, want %q", in, got, want)
		}
	}

	bad := []string{
		"", "ctrl", "ctrl+", "+a", "hyper+a", "ctrl+alt+delete",
		"enter>enter>enter>enter>enter>enter>enter>enter>enter",
		"ctrl+a+b", "a+b", "ctrl+ctrl+a",
	}
	for _, in := range bad {
		if seq, err := ParseSequence(in); err == nil {
			t.Errorf("ParseSequence(%q) = %q, want error", in, seq)
		}
	}
}

func TestSequenceLabel(t *testing.T) {
	seq, err := ParseSequence("home>shift+end>delete")
	if err != nil {
		t.Fatal(err)
	}
	if got := seq.Label(); got != "Home → Shift+End → Delete" {
		t.Fatalf("label = %q", got)
	}
}

func TestIsKnownKey(t *testing.T) {
	for _, name := range []string{"enter", "shift", "backspace", "delete", "pageup", "f1", "f24", "k", "7"} {
		if name == "shift" {
			// Modifiers are not keys; a chord of only "shift" must be rejected.
			if _, err := ParseSequence(name); err == nil {
				t.Error("modifier-only sequence accepted")
			}
			continue
		}
		if !IsKnownKeyName(name) {
			t.Errorf("IsKnownKeyName(%q) = false", name)
		}
	}
	for _, name := range []string{"", "ctrl", "f25", "hyper", "ab"} {
		if IsKnownKeyName(name) {
			t.Errorf("IsKnownKeyName(%q) = true", name)
		}
	}
}
