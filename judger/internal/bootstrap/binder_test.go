package bootstrap

import (
	"context"
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/zheco68-collab/KECE/judger/internal/identity"
)

func TestBinderKeepsClaimAcrossRetryAndStoresCredential(t *testing.T) {
	dir := t.TempDir()
	id, err := identity.LoadOrCreate(filepath.Join(dir, "host.json"), filepath.Join(dir, "node.json"))
	if err != nil {
		t.Fatal(err)
	}
	var previous pending
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != "/internal/judgers/bootstrap/claim" || r.Method != http.MethodPost {
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
			return
		}
		var signed envelope
		if err := json.NewDecoder(r.Body).Decode(&signed); err != nil {
			t.Error(err)
			return
		}
		raw, err := base64.RawURLEncoding.DecodeString(signed.Payload)
		if err != nil {
			t.Error(err)
			return
		}
		var request payload
		if err := json.Unmarshal(raw, &request); err != nil {
			t.Error(err)
			return
		}
		keyBytes, err := base64.RawURLEncoding.DecodeString(signed.PublicKey)
		if err != nil {
			t.Error(err)
			return
		}
		public, err := x509.ParsePKIXPublicKey(keyBytes)
		if err != nil {
			t.Error(err)
			return
		}
		signature, err := base64.RawURLEncoding.DecodeString(signed.Signature)
		if err != nil {
			t.Error(err)
			return
		}
		if !ed25519.Verify(public.(ed25519.PublicKey), []byte("KECE-BOOTSTRAP-CLAIM-V1\n"+signed.Payload), signature) {
			t.Error("invalid claim signature")
		}
		if request.ControlPlaneID != "7cf26574-3858-4e57-a685-8fc947dc4b81" || request.InstanceID != id.InstanceID {
			t.Error("wrong claim identity")
		}
		current := pending{request.InstanceID, request.ClaimID, request.Credential, request.CapabilityDigest}
		if calls == 1 {
			previous = current
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if current != previous {
			t.Error("pending claim changed after retry")
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(confirmation{"1", current.ClaimID, "19", 1, 1, "python", "python-v1"})
	}))
	defer server.Close()
	file := filepath.Join(dir, "credential.json")
	binder := &Binder{
		Identity: id, ControlURL: server.URL, HTTPClient: server.Client(),
		ControlPlaneID: func() string { return "7cf26574-3858-4e57-a685-8fc947dc4b81" },
		CredentialFile: file, BootID: "0123456789abcdef0123456789abcdef",
		Pool: "python", RuntimeSpec: "python-v1", RuntimeVersion: "test-1", LocalSlots: 2,
	}
	ready, err := binder.Try(context.Background())
	if err != nil || ready {
		t.Fatalf("first claim: ready=%v error=%v", ready, err)
	}
	if _, err := os.Stat(file + ".binding.json"); err != nil {
		t.Fatal(err)
	}
	ready, err = binder.Try(context.Background())
	if err != nil || !ready {
		t.Fatalf("retry claim: ready=%v error=%v", ready, err)
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var stored credential
	if err := json.Unmarshal(data, &stored); err != nil {
		t.Fatal(err)
	}
	if stored.NodeID != "19" || stored.Credential != previous.Credential {
		t.Error("credential mismatch")
	}
	if _, err := os.Stat(file + ".binding.json"); !os.IsNotExist(err) {
		t.Error("pending claim not removed")
	}
}

func TestBinderRejectsInsecureRemoteControlPlane(t *testing.T) {
	binder := &Binder{ControlPlaneID: func() string { return "7cf26574-3858-4e57-a685-8fc947dc4b81" },
		ControlURL: "http://10.0.0.5:8080/api/v1", HTTPClient: http.DefaultClient,
		CredentialFile: filepath.Join(t.TempDir(), "credential.json"),
		Identity:       identity.Identity{InstanceID: "cb7e8f06-dd79-4b1e-a6f7-9675a01861a5"}}
	if _, err := binder.Try(context.Background()); err == nil {
		t.Fatal("remote HTTP credential channel accepted")
	}
}

func TestBinderRotatesOnlyExplicitlyStaleClaim(t *testing.T) {
	dir := t.TempDir()
	id, err := identity.LoadOrCreate(filepath.Join(dir, "host.json"), filepath.Join(dir, "node.json"))
	if err != nil {
		t.Fatal(err)
	}
	claims := []string{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var signed envelope
		if err := json.NewDecoder(r.Body).Decode(&signed); err != nil {
			t.Error(err)
			return
		}
		raw, err := base64.RawURLEncoding.DecodeString(signed.Payload)
		if err != nil {
			t.Error(err)
			return
		}
		var request payload
		if err := json.Unmarshal(raw, &request); err != nil {
			t.Error(err)
			return
		}
		claims = append(claims, request.ClaimID)
		if len(claims) == 1 {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if len(claims) == 2 {
			w.WriteHeader(http.StatusConflict)
			_, _ = w.Write([]byte(`{"code":"STALE_NODE_CLAIM"}`))
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	file := filepath.Join(dir, "credential.json")
	binder := &Binder{Identity: id, ControlURL: server.URL, HTTPClient: server.Client(),
		ControlPlaneID: func() string { return "7cf26574-3858-4e57-a685-8fc947dc4b81" },
		CredentialFile: file, BootID: "0123456789abcdef0123456789abcdef",
		Pool: "python", RuntimeSpec: "python-v1", RuntimeVersion: "test-1", LocalSlots: 2}
	for attempt := 0; attempt < 3; attempt++ {
		ready, err := binder.Try(context.Background())
		if err != nil || ready {
			t.Fatalf("attempt %d: ready=%v err=%v", attempt, ready, err)
		}
	}
	if claims[0] != claims[1] || claims[1] == claims[2] {
		t.Fatalf("claim rotation invalid: %v", claims)
	}
}
