package artifact

import (
	"context"
	"crypto/sha256"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/zheco68-collab/KECE/judger/internal/judge"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
)

func fixtureRef(text string) Ref {
	return Ref{Digest: fmt.Sprintf("%x", sha256.Sum256([]byte(text))), Size: int64(len(text))}
}

func TestInterruptedDownloadNeverPublishesPartialFile(t *testing.T) {
	directory := t.TempDir()
	cache, err := NewCache(directory, 1024)
	if err != nil {
		t.Fatal(err)
	}
	ref := fixtureRef("complete")
	if _, err := cache.Acquire(context.Background(), ref, func(context.Context, Ref) (io.ReadCloser, error) {
		return io.NopCloser(strings.NewReader("part")), nil
	}); err == nil {
		t.Fatal("partial content was accepted")
	}
	if entries, err := os.ReadDir(directory); err != nil || len(entries) != 0 {
		t.Fatalf("partial download left files behind: %v, %v", entries, err)
	}
	if _, err := cache.Acquire(context.Background(), ref, func(context.Context, Ref) (io.ReadCloser, error) {
		return io.NopCloser(strings.NewReader("corrupt!")), nil
	}); err == nil {
		t.Fatal("same-size content with a different hash was accepted")
	}
}

func TestConcurrentCacheReadsPinOneVerifiedDownload(t *testing.T) {
	cache, err := NewCache(t.TempDir(), 1)
	if err != nil {
		t.Fatal(err)
	}
	ref := fixtureRef("a shared artifact")
	var calls atomic.Int32
	fetch := func(context.Context, Ref) (io.ReadCloser, error) {
		calls.Add(1)
		return io.NopCloser(strings.NewReader("a shared artifact")), nil
	}
	var handles [8]*Handle
	var wg sync.WaitGroup
	for i := range handles {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			handle, err := cache.Acquire(context.Background(), ref, fetch)
			if err != nil {
				t.Error(err)
				return
			}
			handles[index] = handle
		}(i)
	}
	wg.Wait()
	if calls.Load() != 1 || t.Failed() {
		t.Fatalf("download count = %d", calls.Load())
	}
	if err := cache.Prune(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(handles[0].Path); err != nil {
		t.Fatal("prune removed an active artifact:", err)
	}
	for _, handle := range handles {
		handle.Close()
	}
	if err := cache.Prune(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(handles[0].Path); !os.IsNotExist(err) {
		t.Fatal("idle artifact should have been evicted:", err)
	}
}

func TestLeaseManifestWithoutSharedFiles(t *testing.T) {
	input, output := fixtureRef("2 3"), fixtureRef("")
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-lease" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		switch r.URL.Path {
		case "/api/v1/internal/judgers/jobs/91/artifacts/" + input.Digest:
			_, _ = io.WriteString(w, "2 3")
		case "/api/v1/internal/judgers/jobs/91/artifacts/" + output.Digest:
			w.Header().Set("Content-Length", "0")
			w.WriteHeader(http.StatusOK)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	cache, err := NewCache(t.TempDir(), 1024)
	if err != nil {
		t.Fatal(err)
	}
	fetch, err := LeaseSource(server.Client(), server.URL+"/api/v1", 91, "test-lease")
	if err != nil {
		t.Fatal(err)
	}
	prepared, err := cache.Prepare(context.Background(), Manifest{
		Version: 1, SubmissionType: "ALGO", TimeLimitMs: 2000, MemoryLimitMb: 256,
		Cases: []CaseSpec{{CaseID: 1, Input: &input, Output: &output}},
	}, SourceSnapshot{
		Language: "python",
		CodeContent: map[string]string{
			"main.py": "assert sum(map(int, input().split())) == 5",
		},
	}, fetch)
	if err != nil {
		t.Fatal(err)
	}
	defer prepared.Close()
	got, err := os.ReadFile(prepared.Code.TestCases[0].OutputPath)
	if err != nil || len(got) != 0 || !prepared.Code.RequireCaseFiles {
		t.Fatalf("empty output must be a real, verified artifact: %q, %v", got, err)
	}
	if os.Getenv("KECE_ARTIFACT_SANDBOX_TEST") == "1" {
		runner := &sandbox.Runner{Workspace: t.TempDir(), ImagePython: "python:3.12-slim"}
		engine := judge.NewCode(runner, filepath.Join(t.TempDir(), "no-shared-testcases"))
		verdict, err := engine.Execute(context.Background(), *prepared.Code, nil)
		if err != nil || verdict.Status != "ACCEPTED" {
			t.Fatalf("remote testcase execution failed: %+v, %v", verdict, err)
		}
	}
}

func TestSQLManifestLoadsFrozenReferenceAndSeed(t *testing.T) {
	cache, err := NewCache(t.TempDir(), 1024)
	if err != nil {
		t.Fatal(err)
	}
	const reference = "UPDATE answers SET value = 5"
	const verify = "SELECT value FROM answers"
	const seed = "INSERT INTO answers VALUES (0)"
	referenceRef, verifyRef, seedRef := fixtureRef(reference), fixtureRef(verify), fixtureRef(seed)
	contents := map[string]string{referenceRef.Digest: reference, verifyRef.Digest: verify, seedRef.Digest: seed}
	prepared, err := cache.Prepare(context.Background(), Manifest{
		Version: 1, SubmissionType: "DATABASE", TimeLimitMs: 5000,
		SQL:   &SQLSpec{Dialect: "MYSQL", Type: "DML", Reference: &referenceRef, Verify: &verifyRef},
		Cases: []CaseSpec{{CaseID: 8, Seed: &seedRef}},
	}, SourceSnapshot{
		Language: "mysql", CodeContent: map[string]string{"answer.sql": "UPDATE answers SET value = 4"},
	}, func(_ context.Context, ref Ref) (io.ReadCloser, error) {
		return io.NopCloser(strings.NewReader(contents[ref.Digest])), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer prepared.Close()
	if prepared.SQL.ReferenceSql != reference || prepared.SQL.VerifySql != verify ||
		prepared.SQL.TestCases[0].SeedSql != seed {
		t.Fatalf("SQL inputs changed during preparation: %+v", prepared.SQL)
	}
}
