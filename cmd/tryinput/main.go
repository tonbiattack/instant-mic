//go:build windows

// Command tryinput is a manual check for the SendInput path: it counts down,
// types the supplied text into whatever has focus, and prints focus details.
//
// Pass -expect-title to refuse to type unless the focused window title contains
// the given text, so a manual test never leaks keystrokes into another app.
package main

import (
	"flag"
	"fmt"
	"strings"
	"time"

	"github.com/tonbiattack/instant-mic/internal/input"
	"github.com/tonbiattack/instant-mic/internal/speech"
)

func main() {
	text := flag.String("text", "hello", "text to type")
	key := flag.String("key", "", "key sequence to press: enter, shift+enter, ctrl+z, ctrl+backspace, home>shift+end>delete")
	wait := flag.Duration("wait", 3*time.Second, "delay before typing")
	expect := flag.String("expect-title", "", "refuse to type unless the focused window title contains this text")
	flag.Parse()

	ins := input.New()
	for i := int(wait.Seconds()); i > 0; i-- {
		st := ins.Status()
		fmt.Printf("%d… focus=%v title=%q process=%q elevated=%v\n", i, st.HasFocus, st.Title, st.Process, st.Elevated)
		time.Sleep(time.Second)
	}

	// Check the target again right before injecting: the countdown gives the
	// user time to change windows by accident.
	st := ins.Status()
	if !st.HasFocus {
		fmt.Println("aborted: no focused window")
		return
	}
	if *expect != "" && !strings.Contains(st.Title, *expect) {
		fmt.Printf("aborted: focused window %q does not contain %q\n", st.Title, *expect)
		return
	}

	if *key != "" {
		seq, err := speech.ParseSequence(*key)
		if err != nil {
			fmt.Println("key error:", err)
			return
		}
		if err := ins.PressKey(seq, 1); err != nil {
			fmt.Println("key error:", err)
			return
		}
		fmt.Println("pressed", seq.Label())
		return
	}
	if err := ins.InsertText(*text); err != nil {
		fmt.Println("error:", err)
		return
	}
	fmt.Println("typed", len([]rune(*text)), "characters")
}
