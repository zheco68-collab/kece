package worker

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/artifact"
	"github.com/zheco68-collab/KECE/judger/internal/control"
	"github.com/zheco68-collab/KECE/judger/internal/judge"
)

type mockExecutor struct {
	onExec func(ctx context.Context, task judge.CodeTask, progress judge.ProgressSink) (judge.Verdict, error)
}

func TestUnboundAPIWorkerWaitsUntilStopped(t *testing.T) {
	client, err := control.NewClient("http://127.0.0.1:1/api/v1", nil)
	if err != nil {
		t.Fatal(err)
	}
	w := NewWorker(Config{
		Pool: "python", AwaitConnection: true,
		CredentialFile: filepath.Join(t.TempDir(), "missing-credential.json"),
	}, client, nil, nil, nil, nil)
	ctx, stop := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer stop()
	if err := w.Run(ctx); err != nil {
		t.Fatalf("unbound worker exited with error instead of waiting: %v", err)
	}
	if status := w.HealthSnapshot(); status.NodeID != "" || status.Healthy {
		t.Fatalf("unbound worker reported as active: %+v", status)
	}
}

func (m *mockExecutor) Execute(ctx context.Context, task judge.CodeTask, progress judge.ProgressSink) (judge.Verdict, error) {
	if m.onExec != nil {
		return m.onExec(ctx, task, progress)
	}
	return judge.Verdict{Status: "ACCEPTED"}, nil
}

type mockSQLExecutor struct {
	onExec func(ctx context.Context, task judge.SQLTask, progress judge.ProgressSink) (judge.Verdict, error)
}

func (m *mockSQLExecutor) Execute(ctx context.Context, task judge.SQLTask, progress judge.ProgressSink) (judge.Verdict, error) {
	if m.onExec != nil {
		return m.onExec(ctx, task, progress)
	}
	return judge.Verdict{Status: "ACCEPTED"}, nil
}

func shaStr(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func TestWorkerFullLifecycle(t *testing.T) {
	inputContent := "1 2\n"
	outputContent := "3\n"
	inputDigest := shaStr(inputContent)
	outputDigest := shaStr(outputContent)

	var selfTestSeen atomic.Bool
	var checkReports atomic.Int32
	var checkRuns atomic.Int32
	var jobClaimed atomic.Bool
	var renewSeen atomic.Bool
	var resultReceived atomic.Bool

	mux := http.NewServeMux()

	mux.HandleFunc("/internal/judgers/join", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.Joined{
			NodeID:          "node-py-1",
			Credential:      "tok-1",
			SessionEpoch:    1,
			ProtocolVersion: 1,
			Pool:            "python",
			RuntimeSpec:     "python-v1",
		})
	})

	mux.HandleFunc("/internal/judgers/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		var req control.HeartbeatRequest
		_ = json.NewDecoder(r.Body).Decode(&req)

		state := "PENDING_VERIFICATION"
		var directive *control.CheckDirective

		if req.CheckResult != nil && req.CheckResult.CheckID == "chk-1" {
			switch checkReports.Add(1) {
			case 1:
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			case 2:
				// The server committed the check, but its acknowledgement was lost.
				selfTestSeen.Store(true)
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			case 3:
				w.WriteHeader(http.StatusConflict)
				w.Write([]byte(`{"code":"STALE_CHECK"}`))
				return
			default:
				t.Error("worker kept retrying an already committed check")
			}
		} else if !selfTestSeen.Load() {
			directive = &control.CheckDirective{CheckID: "chk-1", RemainingMs: 30000}
		} else {
			state = "ENABLED"
		}

		json.NewEncoder(w).Encode(control.HeartbeatResponse{
			State:    state,
			Revision: 1,
			MaxSlots: 2,
			Check:    directive,
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/claim", func(w http.ResponseWriter, r *http.Request) {
		if !selfTestSeen.Load() || jobClaimed.Load() {
			json.NewEncoder(w).Encode(control.ClaimBatch{ProtocolVersion: 1, Jobs: nil})
			return
		}
		jobClaimed.Store(true)

		json.NewEncoder(w).Encode(control.ClaimBatch{
			ProtocolVersion: 1,
			Jobs: []control.ClaimedJob{
				{
					JobID:            "888",
					SubmissionType:   "ALGO",
					SubmissionID:     "101",
					Generation:       1,
					Pool:             "python",
					RuntimeSpec:      "python-v1",
					LeaseEpoch:       1,
					LeaseRemainingMs: 450,
					ManifestID:       "man-888",
					ArtifactToken:    "art-tok-888",
					Manifest: artifact.Manifest{
						Version:        1,
						SubmissionType: "ALGO",
						TimeLimitMs:    1000,
						MemoryLimitMb:  256,
						Cases: []artifact.CaseSpec{
							{
								CaseID: 1,
								Input:  &artifact.Ref{Digest: inputDigest, Size: int64(len(inputContent))},
								Output: &artifact.Ref{Digest: outputDigest, Size: int64(len(outputContent))},
							},
						},
					},
					Source: artifact.SourceSnapshot{
						Language: "python",
						CodeContent: map[string]string{
							"main.py": "a, b = map(int, input().split())\nprint(a + b)",
						},
					},
				},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/888/artifacts/"+inputDigest, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(inputContent)))
		w.Write([]byte(inputContent))
	})

	mux.HandleFunc("/internal/judgers/jobs/888/artifacts/"+outputDigest, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(outputContent)))
		w.Write([]byte(outputContent))
	})

	mux.HandleFunc("/internal/judgers/jobs/renew", func(w http.ResponseWriter, r *http.Request) {
		renewSeen.Store(true)
		json.NewEncoder(w).Encode(control.RenewBatch{
			Jobs: []control.Renewed{
				{JobID: "888", Status: "RENEWED", LeaseRemainingMs: 25000},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/888/result", func(w http.ResponseWriter, r *http.Request) {
		var req control.ResultRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Status == "ACCEPTED" {
			resultReceived.Store(true)
		}
		json.NewEncoder(w).Encode(control.Receipt{Status: "COMMITTED"})
	})

	server := httptest.NewTLSServer(mux)
	defer server.Close()

	client, err := control.NewClient(server.URL, server.Client())
	if err != nil {
		t.Fatal(err)
	}

	tempDir := t.TempDir()
	cacheDir := filepath.Join(tempDir, "cache")

	cache, err := artifact.NewCache(cacheDir, 10*1024*1024)
	if err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	exec := &mockExecutor{
		onExec: func(ctx context.Context, task judge.CodeTask, progress judge.ProgressSink) (judge.Verdict, error) {
			duration := 650 * time.Millisecond
			if task.RequireCaseFiles {
				// SIGTERM cancellation must stop claims but leave the active lease running.
				cancel()
			} else {
				checkRuns.Add(1)
				duration = 250 * time.Millisecond
			}
			select {
			case <-ctx.Done():
				return judge.Verdict{}, ctx.Err()
			case <-time.After(duration):
			}
			progress("JUDGING", 1, 1)
			score := 100
			return judge.Verdict{
				Status: "ACCEPTED",
				Score:  &score,
				TestCasesReport: []judge.CaseResult{
					{CaseID: 1, Status: "ACCEPTED"},
				},
			}, nil
		},
	}

	cfg := Config{
		Pool:              "python",
		EnrollmentCode:    "join-code",
		CredentialFile:    filepath.Join(tempDir, "cred.json"),
		BootID:            "boot-1",
		RuntimeSpec:       "python-v1",
		RuntimeVersion:    "3.12",
		LocalSlots:        1,
		ClaimWaitMs:       100,
		HeartbeatInterval: 100 * time.Millisecond,
		RenewInterval:     100 * time.Millisecond,
		SafetyMargin:      50 * time.Millisecond,
	}

	w := NewWorker(cfg, client, cache, exec, nil, nil)

	if err := w.Run(ctx); err != nil {
		t.Fatal(err)
	}

	if !selfTestSeen.Load() {
		t.Error("expected self-test report to be sent")
	}
	if checkReports.Load() != 3 || checkRuns.Load() != 1 {
		t.Errorf("self-test ran %d times with %d report attempts, want 1 run and 3 attempts", checkRuns.Load(), checkReports.Load())
	}
	if !jobClaimed.Load() {
		t.Error("expected job to be claimed")
	}
	if !renewSeen.Load() {
		t.Error("expected lease renewal during drain")
	}
	if !resultReceived.Load() {
		t.Error("expected result to be submitted and received")
	}
}

func TestWorkerLeaseConflictAbortsExecution(t *testing.T) {
	mux := http.NewServeMux()

	mux.HandleFunc("/internal/judgers/join", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.Joined{
			NodeID:          "node-py-2",
			Credential:      "tok-2",
			SessionEpoch:    1,
			ProtocolVersion: 1,
			Pool:            "python",
			RuntimeSpec:     "python-v1",
		})
	})

	mux.HandleFunc("/internal/judgers/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.HeartbeatResponse{
			State:    "ENABLED",
			Revision: 1,
			MaxSlots: 1,
		})
	})

	var claimed atomic.Bool
	mux.HandleFunc("/internal/judgers/jobs/claim", func(w http.ResponseWriter, r *http.Request) {
		if claimed.Swap(true) {
			json.NewEncoder(w).Encode(control.ClaimBatch{ProtocolVersion: 1})
			return
		}
		json.NewEncoder(w).Encode(control.ClaimBatch{
			ProtocolVersion: 1,
			Jobs: []control.ClaimedJob{
				{
					JobID:            "999",
					SubmissionType:   "ALGO",
					SubmissionID:     "102",
					LeaseEpoch:       1,
					LeaseRemainingMs: 30000,
					ManifestID:       "man-999",
					ArtifactToken:    "art-tok-999",
					Manifest: artifact.Manifest{
						Version: 1, SubmissionType: "ALGO", TimeLimitMs: 1000, MemoryLimitMb: 256,
						Cases: []artifact.CaseSpec{
							{CaseID: 1, Input: &artifact.Ref{Digest: shaStr(""), Size: 0}, Output: &artifact.Ref{Digest: shaStr(""), Size: 0}},
						},
					},
					Source: artifact.SourceSnapshot{Language: "python"},
				},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/999/artifacts/"+shaStr(""), func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "0")
		w.WriteHeader(http.StatusOK)
	})

	executionStarted := make(chan struct{})
	// Reject renewal once sandbox execution is active.
	mux.HandleFunc("/internal/judgers/jobs/renew", func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-executionStarted:
		case <-r.Context().Done():
			return
		}
		w.WriteHeader(http.StatusConflict)
		w.Write([]byte(`{"code":"STALE_LEASE"}`))
	})

	mux.HandleFunc("/internal/judgers/jobs/999/result", func(w http.ResponseWriter, r *http.Request) {
		t.Error("worker must not report a result after losing its lease")
		w.WriteHeader(http.StatusConflict)
	})

	server := httptest.NewTLSServer(mux)
	defer server.Close()

	client, _ := control.NewClient(server.URL, server.Client())
	tempDir := t.TempDir()
	cache, _ := artifact.NewCache(filepath.Join(tempDir, "cache"), 10*1024*1024)

	var execCanceled atomic.Bool
	exec := &mockExecutor{
		onExec: func(ctx context.Context, task judge.CodeTask, progress judge.ProgressSink) (judge.Verdict, error) {
			close(executionStarted)
			select {
			case <-ctx.Done():
				execCanceled.Store(true)
				return judge.Verdict{}, ctx.Err()
			case <-time.After(2 * time.Second):
				return judge.Verdict{Status: "ACCEPTED"}, nil
			}
		},
	}

	cfg := Config{
		Pool:              "python",
		EnrollmentCode:    "code",
		CredentialFile:    filepath.Join(tempDir, "cred.json"),
		BootID:            "boot-2",
		RuntimeSpec:       "python-v1",
		RuntimeVersion:    "3.12",
		LocalSlots:        1,
		ClaimWaitMs:       50,
		HeartbeatInterval: 100 * time.Millisecond,
		RenewInterval:     50 * time.Millisecond,
		SafetyMargin:      50 * time.Millisecond,
	}

	w := NewWorker(cfg, client, cache, exec, nil, nil)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	go func() {
		for {
			if execCanceled.Load() {
				cancel()
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()

	_ = w.Run(ctx)

	if !execCanceled.Load() {
		t.Error("expected execution context to be canceled on renew 409 conflict")
	}
}

func TestWorkerResumesClaimsAfterDrainAndDependencyRecovery(t *testing.T) {
	var enabled atomic.Bool
	var available atomic.Bool
	available.Store(true)
	heartbeats := make(chan bool, 16)
	claims := make(chan struct{}, 16)
	mux := http.NewServeMux()
	mux.HandleFunc("/internal/judgers/join", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.Joined{NodeID: "1", Credential: "node-token", SessionEpoch: 1})
	})
	mux.HandleFunc("/internal/judgers/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		var request control.HeartbeatRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		select {
		case heartbeats <- request.Healthy:
		default:
		}
		state := "DRAINING"
		if enabled.Load() {
			state = "ENABLED"
		}
		json.NewEncoder(w).Encode(control.HeartbeatResponse{State: state, MaxSlots: 24})
	})
	mux.HandleFunc("/internal/judgers/jobs/claim", func(w http.ResponseWriter, r *http.Request) {
		var request control.ClaimRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if request.Slots != 16 {
			t.Errorf("claim requested %d slots with 24 available, want protocol maximum 16", request.Slots)
			w.WriteHeader(http.StatusUnprocessableEntity)
			return
		}
		select {
		case claims <- struct{}{}:
		default:
		}
		json.NewEncoder(w).Encode(control.ClaimBatch{ProtocolVersion: 1})
	})
	server := httptest.NewTLSServer(mux)
	defer server.Close()
	client, err := control.NewClient(server.URL, server.Client())
	if err != nil {
		t.Fatal(err)
	}
	worker := NewWorker(Config{
		Pool:              "python",
		EnrollmentCode:    "join",
		LocalSlots:        32,
		HeartbeatInterval: 30 * time.Millisecond,
		HealthCheck: func(context.Context) error {
			if !available.Load() {
				return errors.New("sandbox daemon down")
			}
			return nil
		},
	}, client, nil, nil, nil, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- worker.Run(ctx) }()

	waitHeartbeat := func(healthy bool) {
		t.Helper()
		for {
			select {
			case actual := <-heartbeats:
				if actual == healthy {
					return
				}
			case <-ctx.Done():
				t.Fatalf("no heartbeat reporting healthy=%v", healthy)
			}
		}
	}
	waitClaim := func() {
		t.Helper()
		select {
		case <-claims:
		case <-ctx.Done():
			t.Fatal("node failed to resume claims")
		}
	}
	assertNoClaim := func() {
		t.Helper()
		select {
		case <-claims:
			t.Fatal("node unexpectedly claimed work")
		case <-time.After(300 * time.Millisecond):
		}
	}

	waitHeartbeat(true)
	assertNoClaim()
	enabled.Store(true)
	waitClaim()

	available.Store(false)
	waitHeartbeat(false)
	if worker.HealthSnapshot().Healthy {
		t.Fatal("health endpoint still reports healthy")
	}
	assertNoClaim()
	available.Store(true)
	waitHeartbeat(true)
	waitClaim()
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestWorkerMySQLPoolLifecycle(t *testing.T) {
	schemaSql := "CREATE TABLE users (id INT, name VARCHAR(50));\n"
	refSql := "SELECT * FROM users;\n"
	seedSql := "INSERT INTO users VALUES (1, 'Alice');\n"

	schemaDigest := shaStr(schemaSql)
	refDigest := shaStr(refSql)
	seedDigest := shaStr(seedSql)

	var jobClaimed atomic.Bool
	var sqlExecuted atomic.Bool
	var resultReceived atomic.Bool

	mux := http.NewServeMux()

	mux.HandleFunc("/internal/judgers/join", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.Joined{
			NodeID:          "node-mysql-1",
			Credential:      "tok-sql-1",
			SessionEpoch:    1,
			ProtocolVersion: 1,
			Pool:            "mysql",
			RuntimeSpec:     "mysql-8.0",
		})
	})

	mux.HandleFunc("/internal/judgers/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.HeartbeatResponse{
			State:    "ENABLED",
			Revision: 1,
			MaxSlots: 2,
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/claim", func(w http.ResponseWriter, r *http.Request) {
		if jobClaimed.Swap(true) {
			json.NewEncoder(w).Encode(control.ClaimBatch{ProtocolVersion: 1})
			return
		}

		json.NewEncoder(w).Encode(control.ClaimBatch{
			ProtocolVersion: 1,
			Jobs: []control.ClaimedJob{
				{
					JobID:            "777",
					SubmissionType:   "DATABASE",
					SubmissionID:     "301",
					Generation:       1,
					Pool:             "mysql",
					RuntimeSpec:      "mysql-8.0",
					LeaseEpoch:       1,
					LeaseRemainingMs: 30000,
					ManifestID:       "man-777",
					ArtifactToken:    "art-tok-777",
					Manifest: artifact.Manifest{
						Version:        1,
						SubmissionType: "DATABASE",
						TimeLimitMs:    2000,
						SQL: &artifact.SQLSpec{
							Dialect:        "MYSQL",
							Type:           "SELECT",
							Schema:         &artifact.Ref{Digest: schemaDigest, Size: int64(len(schemaSql))},
							Reference:      &artifact.Ref{Digest: refDigest, Size: int64(len(refSql))},
							OrderSensitive: false,
						},
						Cases: []artifact.CaseSpec{
							{CaseID: 1, ScoreWeight: 100, Seed: &artifact.Ref{Digest: seedDigest, Size: int64(len(seedSql))}},
						},
					},
					Source: artifact.SourceSnapshot{
						Language:    "mysql",
						CodeContent: map[string]string{"answer.sql": "SELECT * FROM users;"},
					},
				},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/777/artifacts/"+schemaDigest, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(schemaSql)))
		w.Write([]byte(schemaSql))
	})
	mux.HandleFunc("/internal/judgers/jobs/777/artifacts/"+refDigest, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(refSql)))
		w.Write([]byte(refSql))
	})
	mux.HandleFunc("/internal/judgers/jobs/777/artifacts/"+seedDigest, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprintf("%d", len(seedSql)))
		w.Write([]byte(seedSql))
	})

	mux.HandleFunc("/internal/judgers/jobs/renew", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(control.RenewBatch{
			Jobs: []control.Renewed{
				{JobID: "777", Status: "RENEWED", LeaseRemainingMs: 25000},
			},
		})
	})

	mux.HandleFunc("/internal/judgers/jobs/777/result", func(w http.ResponseWriter, r *http.Request) {
		var req control.ResultRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.Status == "ACCEPTED" {
			resultReceived.Store(true)
		}
		json.NewEncoder(w).Encode(control.Receipt{Status: "COMMITTED"})
	})

	server := httptest.NewTLSServer(mux)
	defer server.Close()

	client, err := control.NewClient(server.URL, server.Client())
	if err != nil {
		t.Fatal(err)
	}

	tempDir := t.TempDir()
	cache, err := artifact.NewCache(filepath.Join(tempDir, "cache"), 10*1024*1024)
	if err != nil {
		t.Fatal(err)
	}

	sqlExec := &mockSQLExecutor{
		onExec: func(ctx context.Context, task judge.SQLTask, progress judge.ProgressSink) (judge.Verdict, error) {
			sqlExecuted.Store(true)
			progress("JUDGING", 1, 1)
			score := 100
			return judge.Verdict{
				Status: "ACCEPTED",
				Score:  &score,
				TestCasesReport: []judge.CaseResult{
					{CaseID: 1, Status: "ACCEPTED"},
				},
			}, nil
		},
	}

	cfg := Config{
		Pool:              "mysql",
		EnrollmentCode:    "sql-join-code",
		CredentialFile:    filepath.Join(tempDir, "sql-cred.json"),
		BootID:            "boot-sql-1",
		RuntimeSpec:       "mysql-8.0",
		RuntimeVersion:    "8.0.36",
		LocalSlots:        1,
		ClaimWaitMs:       100,
		HeartbeatInterval: 100 * time.Millisecond,
		RenewInterval:     100 * time.Millisecond,
		SafetyMargin:      50 * time.Millisecond,
	}

	w := NewWorker(cfg, client, cache, nil, sqlExec, nil)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	go func() {
		for {
			if resultReceived.Load() {
				cancel()
				return
			}
			time.Sleep(50 * time.Millisecond)
		}
	}()

	_ = w.Run(ctx)

	if !jobClaimed.Load() {
		t.Error("expected mysql job to be claimed")
	}
	if !sqlExecuted.Load() {
		t.Error("expected SQL executor to be called")
	}
	if !resultReceived.Load() {
		t.Error("expected mysql result to be submitted and received")
	}
}

func TestWorkerCompileErrorSerializesEmptyArrayAnd422DoesNotRetry(t *testing.T) {
	inputDigest := shaStr("")
	var claimed atomic.Bool
	var resultCalls atomic.Int32
	var rawBody string

	mux := http.NewServeMux()
	mux.HandleFunc("/internal/judgers/join", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(control.Joined{
			NodeID: "node-cpp-1", Credential: "tok-cpp-1", SessionEpoch: 1, ProtocolVersion: 1,
			Pool: "cpp", RuntimeSpec: "cpp-v1",
		})
	})
	mux.HandleFunc("/internal/judgers/heartbeat", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(control.HeartbeatResponse{State: "ENABLED", Revision: 1, MaxSlots: 1})
	})
	mux.HandleFunc("/internal/judgers/jobs/claim", func(w http.ResponseWriter, r *http.Request) {
		if claimed.Swap(true) {
			_ = json.NewEncoder(w).Encode(control.ClaimBatch{ProtocolVersion: 1})
			return
		}
		_ = json.NewEncoder(w).Encode(control.ClaimBatch{
			ProtocolVersion: 1,
			Jobs: []control.ClaimedJob{{
				JobID: "555", SubmissionType: "ALGO", SubmissionID: "205", Generation: 1,
				Pool: "cpp", RuntimeSpec: "cpp-v1", LeaseEpoch: 1, LeaseRemainingMs: 30000,
				ManifestID: "man-555", ArtifactToken: "art-555",
				Manifest: artifact.Manifest{
					Version: 1, SubmissionType: "ALGO", TimeLimitMs: 1000, MemoryLimitMb: 256,
					Cases: []artifact.CaseSpec{{
						CaseID: 1,
						Input:  &artifact.Ref{Digest: inputDigest, Size: 0},
						Output: &artifact.Ref{Digest: inputDigest, Size: 0},
					}},
				},
				Source: artifact.SourceSnapshot{Language: "cpp", CodeContent: map[string]string{"main.cpp": "bad"}},
			}},
		})
	})
	mux.HandleFunc("/internal/judgers/jobs/555/artifacts/"+inputDigest, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "0")
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/internal/judgers/jobs/555/result", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		rawBody = string(b)
		resultCalls.Add(1)
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"code":"INVALID_RESULT"}`))
	})

	server := httptest.NewServer(mux)
	defer server.Close()

	client, err := control.NewClient(server.URL, server.Client())
	if err != nil {
		t.Fatal(err)
	}
	tempDir := t.TempDir()
	cache, err := artifact.NewCache(filepath.Join(tempDir, "cache"), 10*1024*1024)
	if err != nil {
		t.Fatal(err)
	}
	zero := 0
	errLog := "main.cpp:1:1: error: expected unqualified-id"
	exec := &mockExecutor{
		onExec: func(ctx context.Context, task judge.CodeTask, progress judge.ProgressSink) (judge.Verdict, error) {
			return judge.Verdict{
				Status:          "COMPILE_ERROR",
				Score:           &zero,
				CompileErrorLog: &errLog,
			}, nil
		},
	}

	w := NewWorker(Config{
		Pool: "cpp", EnrollmentCode: "code",
		CredentialFile: filepath.Join(tempDir, "cred.json"), BootID: "boot-cpp-test-123456",
		RuntimeSpec: "cpp-v1", RuntimeVersion: "13", LocalSlots: 1,
		ClaimWaitMs:       50,
		HeartbeatInterval: 100 * time.Millisecond, RenewInterval: 200 * time.Millisecond, SafetyMargin: 50 * time.Millisecond,
	}, client, cache, exec, nil, nil)

	ctx, cancel := context.WithTimeout(context.Background(), 900*time.Millisecond)
	defer cancel()
	_ = w.Run(ctx)

	if resultCalls.Load() != 1 {
		t.Fatalf("expected 422 response not to be retried, got %d calls", resultCalls.Load())
	}
	if !strings.Contains(rawBody, `"testCasesReport":[]`) {
		t.Fatalf("COMPILE_ERROR must serialize testCasesReport as empty array [], got: %s", rawBody)
	}
}
