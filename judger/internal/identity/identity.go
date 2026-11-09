// Package identity persists a host identity shared by workers and a key unique to each worker.
package identity

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Host struct {
	HostID string `json:"hostId"`
}

type Node struct {
	InstanceID string `json:"instanceId"`
	Seed       string `json:"seed"`
}

type Identity struct {
	HostID     string
	InstanceID string
	PublicKey  string
	NodeKeyID  string
	privateKey ed25519.PrivateKey
}

// LoadOrCreate never replaces an existing identity, including one with invalid contents.
// All worker instances on a physical host must receive the same hostPath.
func LoadOrCreate(hostPath, nodePath string) (Identity, error) {
	if hostPath == "" || nodePath == "" || hostPath == nodePath {
		return Identity{}, errors.New("host and node identity paths must be distinct")
	}
	var host Host
	if err := loadOrCreate(hostPath, &host, func() (any, error) {
		id, err := randomUUID()
		return Host{HostID: id}, err
	}); err != nil {
		return Identity{}, fmt.Errorf("host identity: %w", err)
	}
	if !uuidPattern.MatchString(host.HostID) {
		return Identity{}, errors.New("invalid host identity")
	}
	var node Node
	if err := loadOrCreate(nodePath, &node, func() (any, error) {
		id, err := randomUUID()
		if err != nil {
			return nil, err
		}
		_, key, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		return Node{InstanceID: id, Seed: base64.RawURLEncoding.EncodeToString(key.Seed())}, nil
	}); err != nil {
		return Identity{}, fmt.Errorf("node identity: %w", err)
	}
	seed, err := base64.RawURLEncoding.DecodeString(node.Seed)
	if err != nil || len(seed) != ed25519.SeedSize || !uuidPattern.MatchString(node.InstanceID) {
		return Identity{}, errors.New("invalid node identity")
	}
	key := ed25519.NewKeyFromSeed(seed)
	spki, err := x509.MarshalPKIXPublicKey(key.Public())
	if err != nil {
		return Identity{}, err
	}
	digest := sha256.Sum256(spki)
	return Identity{
		HostID: host.HostID, InstanceID: node.InstanceID,
		PublicKey: base64.RawURLEncoding.EncodeToString(spki),
		NodeKeyID: hex.EncodeToString(digest[:]), privateKey: key,
	}, nil
}

func (i Identity) Sign(message []byte) []byte {
	return ed25519.Sign(i.privateKey, message)
}

func loadOrCreate(path string, dest any, create func() (any, error)) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		value, createErr := create()
		if createErr != nil {
			return createErr
		}
		data, createErr = json.Marshal(value)
		if createErr != nil {
			return createErr
		}
		file, openErr := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if openErr == nil {
			_, writeErr := file.Write(data)
			if writeErr == nil {
				writeErr = file.Sync()
			}
			closeErr := file.Close()
			if writeErr != nil || closeErr != nil {
				_ = os.Remove(path)
				return errors.Join(writeErr, closeErr)
			}
		} else if errors.Is(openErr, os.ErrExist) {
			data, err = os.ReadFile(path)
			if err != nil {
				return err
			}
		} else {
			return openErr
		}
	} else if err != nil {
		return err
	}
	// Another process may have won O_EXCL but still be writing its small identity file.
	for attempt := 0; !json.Valid(data) && attempt < 20; attempt++ {
		time.Sleep(10 * time.Millisecond)
		data, err = os.ReadFile(path)
		if err != nil {
			return err
		}
	}
	return json.Unmarshal(data, dest)
}

func randomUUID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", err
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:]), nil
}
