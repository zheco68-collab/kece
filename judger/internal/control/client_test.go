package control

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestJoinDoesNotForwardEnrollmentCodeOnRedirect(t *testing.T) {
	var forwarded atomic.Bool
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		forwarded.Store(true)
	}))
	defer target.Close()
	issuer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusTemporaryRedirect)
	}))
	defer issuer.Close()
	client, err := NewClient(issuer.URL, issuer.Client())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Join(context.Background(), "test-enrollment-code", "test-boot"); err == nil {
		t.Fatal("expected enrollment redirect to fail")
	}
	if forwarded.Load() {
		t.Fatal("enrollment was forwarded to the redirect target")
	}
}

func TestClientJoinSessionHeartbeatClaimRenewResult(t *testing.T) {
	mux := http.NewServeMux()

	mux.HandleFunc("/internal/judgers/join", func(w http.ResponseWriter, r *http.Request) {
		var req JoinRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if req.EnrollmentCode != "valid-code" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		json.NewEncoder(w).Encode(Joined{
			NodeID:          "node-1",
			Credential:      "cred-123",
			SessionEpoch:    1,
			ProtocolVersion: 1,
			Pool:            "python",
			RuntimeSpec:     "python-v1",
		})
	})

	mux.HandleFunc("/internal/judgers/session", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer cred-123" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		json.NewEncoder(w).Encode(Session{
			NodeID:          "node-1",
			SessionEpoch:    2,
			ProtocolVersion: 1,
			Pool:            "python",
			RuntimeSpec:     "python-v1",
		})
	})

	mux.HandleFunc("/internal/judgers/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer cred-123" || r.Header.Get("X-Judge-Session-Epoch") != "2" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		json.NewEncoder(w).Encode(HeartbeatResponse{
			State:    "ENABLED",
			Revision: 1,
			MaxSlots: 2,
			Check: &CheckDirective{
				CheckID:     "chk-99",
				RemainingMs: 30000,
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/claim", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer cred-123" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		json.NewEncoder(w).Encode(ClaimBatch{
			ProtocolVersion: 1,
			Jobs: []ClaimedJob{
				{
					JobID:            "1001",
					SubmissionType:   "ALGO",
					SubmissionID:     "501",
					Generation:       1,
					Pool:             "python",
					RuntimeSpec:      "python-v1",
					LeaseEpoch:       1,
					LeaseRemainingMs: 59000,
					ManifestID:       "man-1",
					ArtifactToken:    "token-art-1",
				},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/renew", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(RenewBatch{
			Jobs: []Renewed{
				{JobID: "1001", Status: "RENEWED", LeaseRemainingMs: 55000},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/1001/result", func(w http.ResponseWriter, r *http.Request) {
		var req ResultRequest
		json.NewDecoder(r.Body).Decode(&req)
		if req.LeaseEpoch != 1 || req.Status != "ACCEPTED" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		json.NewEncoder(w).Encode(Receipt{Status: "COMMITTED"})
	})

	server := httptest.NewServer(mux)
	defer server.Close()

	client, err := NewClient(server.URL, server.Client())
	if err != nil {
		t.Fatal(err)
	}

	ctx := context.Background()

	// 1. Join
	joined, err := client.Join(ctx, "valid-code", "boot-1")
	if err != nil {
		t.Fatalf("Join failed: %v", err)
	}
	if joined.NodeID != "node-1" || joined.Credential != "cred-123" {
		t.Fatalf("unexpected joined: %+v", joined)
	}

	// 2. Session
	sess, err := client.Session(ctx, "boot-2")
	if err != nil {
		t.Fatalf("Session failed: %v", err)
	}
	if sess.SessionEpoch != 2 {
		t.Fatalf("expected epoch 2, got %d", sess.SessionEpoch)
	}

	// 3. Heartbeat
	hb, err := client.Heartbeat(ctx, HeartbeatRequest{
		RuntimeSpec:    "python-v1",
		RuntimeVersion: "3.12.0",
		LocalSlots:     2,
		Healthy:        true,
	})
	if err != nil {
		t.Fatalf("Heartbeat failed: %v", err)
	}
	if hb.State != "ENABLED" || hb.Check == nil || hb.Check.CheckID != "chk-99" {
		t.Fatalf("unexpected heartbeat: %+v", hb)
	}

	// 4. Claim
	claimed, err := client.Claim(ctx, 1, 0)
	if err != nil {
		t.Fatalf("Claim failed: %v", err)
	}
	if len(claimed.Jobs) != 1 || claimed.Jobs[0].JobID != "1001" {
		t.Fatalf("unexpected claim: %+v", claimed)
	}

	// 5. Renew
	renewed, err := client.Renew(ctx, []Progress{
		{JobID: "1001", LeaseEpoch: 1, Sequence: 1, Phase: "JUDGING", FinishedCases: 1},
	})
	if err != nil {
		t.Fatalf("Renew failed: %v", err)
	}
	if len(renewed) != 1 || renewed[0].Status != "RENEWED" {
		t.Fatalf("unexpected renew: %+v", renewed)
	}

	// 6. Result
	receipt, err := client.Result(ctx, "1001", ResultRequest{
		LeaseEpoch: 1,
		Status:     "ACCEPTED",
	})
	if err != nil {
		t.Fatalf("Result failed: %v", err)
	}
	if receipt.Status != "COMMITTED" {
		t.Fatalf("unexpected receipt: %+v", receipt)
	}
}
