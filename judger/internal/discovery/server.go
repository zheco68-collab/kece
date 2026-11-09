// Package discovery exposes the unauthenticated, read-only worker identity probe.
package discovery

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/zheco68-collab/KECE/judger/internal/identity"
)

const maxRequestBytes = 4096

var noncePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
var controlIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Request struct {
	DiscoveryVersion int    `json:"discoveryVersion"`
	ControlPlaneID   string `json:"controlPlaneId"`
	Nonce            string `json:"nonce"`
}

type Payload struct {
	DiscoveryVersion   int    `json:"discoveryVersion"`
	JobProtocolVersion int    `json:"jobProtocolVersion"`
	ControlPlaneID     string `json:"controlPlaneId"`
	InstanceID         string `json:"instanceId"`
	HostID             string `json:"hostId"`
	NodeKeyID          string `json:"nodeKeyId"`
	BootID             string `json:"bootId"`
	Pool               string `json:"pool"`
	RuntimeSpec        string `json:"runtimeSpec"`
	RuntimeVersion     string `json:"runtimeVersion"`
	DisplayName        string `json:"displayName"`
	LocalSlots         int    `json:"localSlots"`
	BindingState       string `json:"bindingState"`
	DependencyState    string `json:"dependencyState"`
	CapabilityDigest   string `json:"capabilityDigest"`
	Nonce              string `json:"nonce"`
}

type Envelope struct {
	PublicKey string `json:"publicKey"`
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}

type Snapshot func() (bindingState, dependencyState string)

func Handler(id identity.Identity, controlPlaneID func() string, bootID, pool, runtimeSpec, runtimeVersion, displayName string, localSlots int, snapshot Snapshot) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", http.MethodPost)
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		controlID := controlPlaneID()
		if !controlIDPattern.MatchString(controlID) {
			http.Error(w, "control plane identity unavailable", http.StatusServiceUnavailable)
			return
		}
		var input Request
		r.Body = http.MaxBytesReader(w, r.Body, maxRequestBytes)
		dec := json.NewDecoder(r.Body)
		dec.DisallowUnknownFields()
		if err := dec.Decode(&input); err != nil {
			http.Error(w, "invalid discovery request", http.StatusBadRequest)
			return
		}
		var extra any
		if err := dec.Decode(&extra); err != io.EOF || r.ContentLength > maxRequestBytes {
			http.Error(w, "invalid discovery request", http.StatusBadRequest)
			return
		}
		if input.DiscoveryVersion != 1 || input.ControlPlaneID != controlID || !noncePattern.MatchString(input.Nonce) {
			http.Error(w, "discovery version or control plane mismatch", http.StatusConflict)
			return
		}
		binding, dependency := snapshot()
		payload := Payload{
			DiscoveryVersion: 1, JobProtocolVersion: 1, ControlPlaneID: controlID,
			InstanceID: id.InstanceID, HostID: id.HostID, NodeKeyID: id.NodeKeyID,
			BootID: bootID, Pool: pool, RuntimeSpec: runtimeSpec,
			RuntimeVersion: runtimeVersion, DisplayName: displayName, LocalSlots: localSlots,
			BindingState: binding, DependencyState: dependency,
			CapabilityDigest: CapabilityDigest(id, pool, runtimeSpec, runtimeVersion, localSlots),
			Nonce:            input.Nonce,
		}
		bytes, err := json.Marshal(payload)
		if err != nil {
			http.Error(w, "discovery unavailable", http.StatusServiceUnavailable)
			return
		}
		encoded := base64.RawURLEncoding.EncodeToString(bytes)
		envelope := Envelope{
			PublicKey: id.PublicKey, Payload: encoded,
			Signature: base64.RawURLEncoding.EncodeToString(id.Sign([]byte("KECE-DISCOVERY-V1\n" + encoded))),
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(envelope)
	})
}

func CapabilityDigest(id identity.Identity, pool, runtimeSpec, runtimeVersion string, localSlots int) string {
	fields := []string{id.InstanceID, id.HostID, pool, runtimeSpec, runtimeVersion, strconv.Itoa(localSlots), id.NodeKeyID}
	sum := sha256.Sum256([]byte("KECE-CAPABILITY-V1\n" + strings.Join(fields, "\n")))
	return hex.EncodeToString(sum[:])
}

func ValidateConfiguration(controlPlaneID, pool, runtimeSpec, runtimeVersion string, localSlots int) error {
	if !ValidControlPlaneID(controlPlaneID) {
		return fmt.Errorf("invalid control plane identity")
	}
	if !regexp.MustCompile(`^(cpp|java|python|mysql|arkts)$`).MatchString(pool) ||
		!regexp.MustCompile(`^[A-Za-z0-9._+-]{1,64}$`).MatchString(runtimeSpec) ||
		!regexp.MustCompile(`^[A-Za-z0-9._+-]{1,100}$`).MatchString(runtimeVersion) ||
		localSlots < 1 || localSlots > 256 {
		return fmt.Errorf("invalid worker discovery configuration")
	}
	return nil
}

func ValidControlPlaneID(id string) bool { return controlIDPattern.MatchString(id) }
