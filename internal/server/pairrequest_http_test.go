package server

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

// These exercise the real HTTP endpoints, because the security rules matter at
// the boundary: an unapproved request must never receive a usable secret.

func postPairRequest(t *testing.T, base, deviceID string) (*http.Response, []byte) {
	t.Helper()
	body := strings.NewReader(`{"deviceId":"` + deviceID + `","deviceName":"テスト端末"}`)
	resp, err := http.Post(base+"/pair/request", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

func TestPairRequestEndpointApprovesAndPairs(t *testing.T) {
	env := newTestEnv(t)
	// Approve automatically, as a user clicking "yes" would.
	env.srv.onPairRequest = func(req PairRequest) bool { return true }

	resp, data := postPairRequest(t, env.httpURL, "req-device-1")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d: %s", resp.StatusCode, data)
	}
	var created pairRequestCreated
	if err := json.Unmarshal(data, &created); err != nil {
		t.Fatal(err)
	}
	if created.State != PairRequestPending {
		t.Fatalf("initial state = %q", created.State)
	}

	// Poll until the approval lands (the callback runs on its own goroutine).
	var secret string
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(env.httpURL + "/pair/request/" + created.ID)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var status pairRequestStatus
		if err := json.Unmarshal(body, &status); err != nil {
			t.Fatal(err)
		}
		if status.State == PairRequestApproved && status.Secret != "" {
			secret = status.Secret
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if secret == "" {
		t.Fatal("never received a secret after approval")
	}

	// The secret must work on the normal pairing endpoint, so the phone needs no
	// special case.
	pairBody := strings.NewReader(`{"qrSecret":"` + secret + `","deviceId":"req-device-1","deviceName":"テスト端末"}`)
	pairResp, err := http.Post(env.httpURL+"/pair", "application/json", pairBody)
	if err != nil {
		t.Fatal(err)
	}
	defer pairResp.Body.Close()
	if pairResp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(pairResp.Body)
		t.Fatalf("pairing with the approved secret failed: %d %s", pairResp.StatusCode, body)
	}
}

func TestPairRequestDeniedGivesNoSecret(t *testing.T) {
	env := newTestEnv(t)
	env.srv.onPairRequest = func(req PairRequest) bool { return false }

	_, data := postPairRequest(t, env.httpURL, "req-device-2")
	var created pairRequestCreated
	if err := json.Unmarshal(data, &created); err != nil {
		t.Fatal(err)
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(env.httpURL + "/pair/request/" + created.ID)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var status pairRequestStatus
		if err := json.Unmarshal(body, &status); err != nil {
			t.Fatal(err)
		}
		if status.State == PairRequestDenied {
			if status.Secret != "" {
				t.Fatal("denied request returned a secret")
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("request was never denied")
}

func TestPairRequestEndpointDisabledWithoutApprover(t *testing.T) {
	env := newTestEnv(t)
	// No approver: the receiver cannot ask the user, so it must refuse rather
	// than leave the phone waiting.
	env.srv.onPairRequest = nil

	resp, _ := postPairRequest(t, env.httpURL, "req-device-3")
	if resp.StatusCode != http.StatusNotImplemented {
		t.Fatalf("status = %d, want 501", resp.StatusCode)
	}
}

func TestPairRequestStatusRejectsUnknown(t *testing.T) {
	env := newTestEnv(t)
	resp, err := http.Get(env.httpURL + "/pair/request/does-not-exist")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", resp.StatusCode)
	}
}

func TestPairRequestRejectsBadDeviceID(t *testing.T) {
	env := newTestEnv(t)
	env.srv.onPairRequest = func(req PairRequest) bool { return false }

	body := strings.NewReader(`{"deviceId":"","deviceName":"x"}`)
	resp, err := http.Post(env.httpURL+"/pair/request", "application/json", body)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", resp.StatusCode)
	}
}
