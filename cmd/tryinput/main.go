//go:build windows

// Command tryinput is a manual check for the SendInput path: it counts down,
// types the supplied text into whatever has focus, and prints focus details.
package main

import (
	"flag"
	"fmt"
	"time"

	"github.com/tonbiattack/instant-mic/internal/input"
	"github.com/tonbiattack/instant-mic/internal/speech"
)

func main() {
	text := flag.String("text", "hello", "text to type")
	key := flag.String("key", "", "key to press: enter, shift+enter, ctrl+z, ctrl+a")
	wait := flag.Duration("wait", 3*time.Second, "delay before typing")
	flag.Parse()

	ins := input.New()
	for i := int(wait.Seconds()); i > 0; i-- {
		st := ins.Status()
		fmt.Printf("%d… focus=%v title=%q process=%q elevated=%v\n", i, st.HasFocus, st.Title, st.Process, st.Elevated)
		time.Sleep(time.Second)
	}

	if *key != "" {
		if err := ins.PressKey(speech.Key(*key)); err != nil {
			fmt.Println("key error:", err)
			return
		}
		fmt.Println("pressed", *key)
		return
	}
	if err := ins.InsertText(*text); err != nil {
		fmt.Println("error:", err)
		return
	}
	fmt.Println("typed", len([]rune(*text)), "characters")
}
