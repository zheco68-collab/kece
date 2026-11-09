package discovery

import (
	"bytes"
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/zheco68-collab/KECE/judger/internal/identity"
)

func TestSignedDiscoveryBindsChallengeAndControlPlane(t *testing.T) {
	dir := t.TempDir()
	id, err := identity.LoadOrCreate(filepath.Join(dir, "host.json"), filepath.Join(dir, "node.json"))
	if err != nil {
		t.Fatal(err)
	}
	controlID := "7cf26574-3858-4e57-a685-8fc947dc4b81"
	handler := Handler(id, func() string { return controlID }, "boot-1234567890123456", "python", "python-v1", "prod-1.1", "python-1", 2, func() (string, string) {
		return "UNBOUND", "READY"
	})
	nonce := strings.Repeat("A", 43)
	request := httptest.NewRequest(http.MethodPost, "/v1/node/info", bytes.NewBufferString(`{"discoveryVersion":1,"controlPlaneId":"`+controlID+`","nonce":"`+nonce+`"}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("unexpected discovery response: %d %s", response.Code, response.Body.String())
	}
	var envelope Envelope
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	spki, _ := base64.RawURLEncoding.DecodeString(envelope.PublicKey)
	key, err := x509.ParsePKIXPublicKey(spki)
	if err != nil {
		t.Fatal(err)
	}
	signature, _ := base64.RawURLEncoding.DecodeString(envelope.Signature)
	if !ed25519.Verify(key.(ed25519.PublicKey), []byte("KECE-DISCOVERY-V1\n"+envelope.Payload), signature) {
		t.Fatal("discovery signature failed")
	}
	payloadBytes, _ := base64.RawURLEncoding.DecodeString(envelope.Payload)
	var payload Payload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil || payload.Nonce != nonce || payload.Pool != "python" || payload.HostID != id.HostID || payload.CapabilityDigest != CapabilityDigest(id, "python", "python-v1", "prod-1.1", 2) {
		t.Fatalf("unexpected signed payload: %+v %v", payload, err)
	}
	bad := httptest.NewRequest(http.MethodPost, "/v1/node/info", bytes.NewBufferString(`{"discoveryVersion":1,"controlPlaneId":"wrong","nonce":"`+nonce+`"}`))
	denied := httptest.NewRecorder()
	handler.ServeHTTP(denied, bad)
	if denied.Code != http.StatusConflict {
		t.Fatalf("wrong control plane was accepted: %d", denied.Code)
	}
}
