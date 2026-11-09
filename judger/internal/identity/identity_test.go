package identity

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"testing"
)

func TestLoadOrCreateKeepsHostAndNodeIdentity(t *testing.T) {
	dir := t.TempDir()
	hostPath := filepath.Join(dir, "host.json")
	first, err := LoadOrCreate(hostPath, filepath.Join(dir, "node-a.json"))
	if err != nil {
		t.Fatal(err)
	}
	restarted, err := LoadOrCreate(hostPath, filepath.Join(dir, "node-a.json"))
	if err != nil || !reflect.DeepEqual(first, restarted) {
		t.Fatalf("node identity changed after restart: %v", err)
	}
	second, err := LoadOrCreate(hostPath, filepath.Join(dir, "node-b.json"))
	if err != nil || first.HostID != second.HostID || first.InstanceID == second.InstanceID {
		t.Fatalf("worker instances did not share host identity: %v", err)
	}
	spki, err := base64.RawURLEncoding.DecodeString(first.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	key, err := x509.ParsePKIXPublicKey(spki)
	if err != nil || !ed25519.Verify(key.(ed25519.PublicKey), []byte("challenge"), first.Sign([]byte("challenge"))) {
		t.Fatalf("persisted key cannot sign: %v", err)
	}
}

func TestConcurrentWorkersShareOneHostIdentity(t *testing.T) {
	dir := t.TempDir()
	hostPath := filepath.Join(dir, "host.json")
	const workers = 20
	var group sync.WaitGroup
	ids := make(chan string, workers)
	errors := make(chan error, workers)
	for n := 0; n < workers; n++ {
		group.Add(1)
		go func(n int) {
			defer group.Done()
			id, err := LoadOrCreate(hostPath, filepath.Join(dir, fmt.Sprintf("node-%d.json", n)))
			if err != nil {
				errors <- err
				return
			}
			ids <- id.HostID
		}(n)
	}
	group.Wait()
	close(ids)
	close(errors)
	for err := range errors {
		t.Fatal(err)
	}
	var expected string
	for hostID := range ids {
		if expected == "" {
			expected = hostID
		} else if hostID != expected {
			t.Fatalf("workers were assigned different host identities: %s and %s", expected, hostID)
		}
	}
}

func TestInvalidIdentityFailsClosed(t *testing.T) {
	dir := t.TempDir()
	hostPath := filepath.Join(dir, "host.json")
	if err := os.WriteFile(hostPath, []byte(`{"hostId":"not-a-uuid"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadOrCreate(hostPath, filepath.Join(dir, "node.json")); err == nil {
		t.Fatal("malformed host identity was replaced or accepted")
	}
}
