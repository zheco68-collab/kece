// Package bootstrap exchanges a signed worker identity for an approved node credential.
package bootstrap

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/discovery"
	"github.com/zheco68-collab/KECE/judger/internal/identity"
)

var decimalID = regexp.MustCompile(`^[1-9][0-9]*$`)
var claimID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Binder struct {
	Identity       identity.Identity
	ControlURL     string
	HTTPClient     *http.Client
	ControlPlaneID func() string
	CredentialFile string
	BootID         string
	Pool           string
	RuntimeSpec    string
	RuntimeVersion string
	LocalSlots     int
}

type pending struct {
	InstanceID       string `json:"instanceId"`
	ClaimID          string `json:"claimId"`
	Credential       string `json:"credential"`
	CapabilityDigest string `json:"capabilityDigest"`
}

type payload struct {
	ProtocolVersion  int    `json:"protocolVersion"`
	ControlPlaneID   string `json:"controlPlaneId"`
	InstanceID       string `json:"instanceId"`
	NodeKeyID        string `json:"nodeKeyId"`
	CapabilityDigest string `json:"capabilityDigest"`
	ClaimID          string `json:"claimId"`
	BootID           string `json:"bootId"`
	IssuedAt         int64  `json:"issuedAt"`
	Nonce            string `json:"nonce"`
	Credential       string `json:"credential"`
}

type envelope struct {
	PublicKey string `json:"publicKey"`
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}

type confirmation struct {
	ConnectionID      string `json:"connectionId"`
	ClaimID           string `json:"claimId"`
	NodeID            string `json:"nodeId"`
	BindingGeneration int64  `json:"bindingGeneration"`
	ProtocolVersion   int    `json:"protocolVersion"`
	Pool              string `json:"pool"`
	RuntimeSpec       string `json:"runtimeSpec"`
}

type credential struct {
	NodeID     string `json:"nodeId"`
	Credential string `json:"credential"`
}

// Try returns false when the administrator has not yet approved this instance.
// A pending claim is durable so a lost confirmation retries the same credential.
func (b *Binder) Try(ctx context.Context) (bool, error) {
	if b.ControlPlaneID == nil || !discovery.ValidControlPlaneID(b.ControlPlaneID()) {
		return false, nil
	}
	if b.CredentialFile == "" || b.HTTPClient == nil || b.Identity.InstanceID == "" {
		return false, errors.New("invalid bootstrap configuration")
	}
	controlURL, err := url.Parse(b.ControlURL)
	if err != nil || controlURL.Hostname() == "" ||
		(controlURL.Scheme != "https" && !isLoopbackControl(controlURL)) {
		return false, errors.New("bootstrap control plane must use HTTPS outside loopback")
	}
	capability := discovery.CapabilityDigest(b.Identity, b.Pool, b.RuntimeSpec, b.RuntimeVersion, b.LocalSlots)
	bindingPath := b.CredentialFile + ".binding.json"
	candidate, err := loadOrCreatePending(bindingPath, b.Identity.InstanceID, capability)
	if err != nil {
		return false, err
	}
	nonce, err := randomToken()
	if err != nil {
		return false, err
	}
	message := payload{1, b.ControlPlaneID(), b.Identity.InstanceID, b.Identity.NodeKeyID,
		capability, candidate.ClaimID, b.BootID, time.Now().UnixMilli(), nonce, candidate.Credential}
	rawPayload, err := json.Marshal(message)
	if err != nil {
		return false, err
	}
	encoded := base64.RawURLEncoding.EncodeToString(rawPayload)
	body, err := json.Marshal(envelope{b.Identity.PublicKey, encoded,
		base64.RawURLEncoding.EncodeToString(b.Identity.Sign([]byte("KECE-BOOTSTRAP-CLAIM-V1\n" + encoded)))})
	if err != nil {
		return false, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		b.ControlURL+"/internal/judgers/bootstrap/claim", bytes.NewReader(body))
	if err != nil {
		return false, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := b.HTTPClient.Do(request)
	if err != nil {
		return false, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNoContent {
		return false, nil
	}
	if response.StatusCode == http.StatusConflict {
		var rejected struct {
			Code string `json:"code"`
		}
		if json.NewDecoder(io.LimitReader(response.Body, 1024)).Decode(&rejected) == nil && rejected.Code == "STALE_NODE_CLAIM" {
			if err := os.Remove(bindingPath); err != nil && !errors.Is(err, os.ErrNotExist) {
				return false, err
			}
			return false, nil
		}
	}
	if response.StatusCode != http.StatusOK {
		return false, fmt.Errorf("bootstrap claim rejected: HTTP %d", response.StatusCode)
	}
	var result confirmation
	if err := json.NewDecoder(io.LimitReader(response.Body, 16*1024)).Decode(&result); err != nil {
		return false, err
	}
	if result.ClaimID != candidate.ClaimID || !decimalID.MatchString(result.ConnectionID) ||
		!decimalID.MatchString(result.NodeID) || result.BindingGeneration < 1 ||
		result.ProtocolVersion != 1 || result.Pool != b.Pool || result.RuntimeSpec != b.RuntimeSpec {
		return false, errors.New("bootstrap confirmation mismatch")
	}
	if err := writeAtomic(b.CredentialFile, credential{result.NodeID, candidate.Credential}); err != nil {
		return false, fmt.Errorf("store node credential: %w", err)
	}
	if err := os.Remove(bindingPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return false, err
	}
	return true, nil
}

func isLoopbackControl(u *url.URL) bool {
	host := u.Hostname()
	return u.Scheme == "http" && (host == "localhost" || host == "127.0.0.1" || host == "::1")
}

func loadOrCreatePending(path, instanceID, capability string) (pending, error) {
	var existing pending
	data, err := os.ReadFile(path)
	if err == nil {
		if json.Unmarshal(data, &existing) != nil || existing.InstanceID != instanceID ||
			existing.CapabilityDigest != capability || !claimID.MatchString(existing.ClaimID) ||
			!validToken(existing.Credential) {
			return pending{}, errors.New("invalid existing bootstrap claim")
		}
		return existing, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return pending{}, err
	}
	id, err := randomUUID()
	if err != nil {
		return pending{}, err
	}
	token, err := randomToken()
	if err != nil {
		return pending{}, err
	}
	created := pending{instanceID, id, token, capability}
	if err := writeExclusive(path, created); err != nil {
		if errors.Is(err, os.ErrExist) {
			return loadOrCreatePending(path, instanceID, capability)
		}
		return pending{}, err
	}
	return created, nil
}

func writeExclusive(path string, value any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	_, writeErr := file.Write(data)
	if writeErr == nil {
		writeErr = file.Sync()
	}
	closeErr := file.Close()
	if writeErr != nil || closeErr != nil {
		_ = os.Remove(path)
		return errors.Join(writeErr, closeErr)
	}
	return nil
}

func writeAtomic(path string, value any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	temporary := path + "." + strconv.FormatInt(time.Now().UnixNano(), 10) + ".tmp"
	file, err := os.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	_, writeErr := file.Write(data)
	if writeErr == nil {
		writeErr = file.Sync()
	}
	closeErr := file.Close()
	if writeErr != nil || closeErr != nil {
		_ = os.Remove(temporary)
		return errors.Join(writeErr, closeErr)
	}
	if err := os.Rename(temporary, path); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}

func randomToken() (string, error) {
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(bytes[:]), nil
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

func validToken(token string) bool {
	if len(token) != 43 {
		return false
	}
	for _, c := range token {
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
