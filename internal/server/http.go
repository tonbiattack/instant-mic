package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"unicode/utf8"

	"github.com/tonbiattack/instant-mic/internal/speech"
)

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(v)
}

func decodeJSON(r *http.Request, v any) error {
	if r.Body == nil {
		return errors.New("empty body")
	}
	dec := json.NewDecoder(io.LimitReader(r.Body, 64<<10))
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

// validateText enforces the text limits shared with the client.
func validateText(text string) error {
	if text == "" {
		return errors.New("empty text")
	}
	if !utf8.ValidString(text) {
		return errors.New("text is not valid UTF-8")
	}
	if n := utf8.RuneCountInString(text); n > speech.MaxTextRunes {
		return fmt.Errorf("text is too long (%d runes)", n)
	}
	return nil
}

// validateLiteral additionally refuses a settled phrase that looks like the
// wake prefix. The phone disables incremental input for commands, so a literal
// phrase starting with it means the sides disagree; refusing is safer than
// typing something the final result will also type.
func validateLiteral(text string) error {
	if err := validateText(text); err != nil {
		return err
	}
	normalized := speech.Normalize(text)
	if normalized == "" {
		return errors.New("empty text")
	}
	// Any prefix of the wake word counts: the phrase may be the beginning of a
	// command that the final result will interpret.
	prefix := speech.Normalize(speech.CommandPrefix)
	limit := len([]rune(normalized))
	if n := len([]rune(prefix)); n < limit {
		limit = n
	}
	runes := []rune(normalized)
	prefixRunes := []rune(prefix)
	for i := 1; i <= limit; i++ {
		if string(runes[:i]) == string(prefixRunes[:i]) {
			return errors.New("コマンドとして解釈される可能性があるため、途中入力しません")
		}
	}
	return nil
}
