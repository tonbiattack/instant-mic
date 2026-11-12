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
