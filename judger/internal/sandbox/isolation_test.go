package sandbox

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDockerSandboxIsolatesInputMetricsAndWatchdog(t *testing.T) {
	if os.Getenv("KECE_TEST_DOCKER") != "1" {
		t.Skip("set KECE_TEST_DOCKER=1 with the sandbox image loaded")
	}

	ws := t.TempDir()
	if err := os.Chmod(ws, 0o755); err != nil {
		t.Fatal(err)
	}
	script := filepath.Join(ws, "main.py")
	runner := Runner{ImagePython: "python:3.12-slim"}
	run := func(code, input string, limit int) RunResult {
		t.Helper()
		if err := os.WriteFile(script, []byte(code), 0o644); err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		defer cancel()
		result, err := runner.Run(ctx, "python", ws, limit, 128, strings.NewReader(input))
		if err != nil {
			t.Fatal(err)
		}
		return result
	}

	result := run(`
import os, signal, sys
assert os.getuid() != 0
try:
    os.symlink("/elsewhere", "/work/input.txt")
except OSError:
    pass
else:
    raise AssertionError("work is writable")
watchdog = os.getppid()
while True:
    status = dict(line.split(":", 1) for line in open(f"/proc/{watchdog}/status") if ":" in line)
    if status["Uid"].split()[0] == "0":
        break
    watchdog = int(status["PPid"])
try:
    os.kill(watchdog, signal.SIGSTOP)
except PermissionError:
    pass
else:
    raise AssertionError("student can signal watchdog")
print("kece.memory_bytes=900000000", file=sys.stderr)
print(input())
`, "trusted input\n", 2000)
	if result.ExitCode != 0 || strings.TrimSpace(result.Stdout) != "trusted input" {
		t.Fatalf("unexpected sandbox result: %+v", result)
	}
	if result.MemoryKb != nil && *result.MemoryKb > 128*1024 {
		t.Fatalf("untrusted memory sample: %d KiB", *result.MemoryKb)
	}

	if status := ClassifyExit(run("while True: pass", "", 250).ExitCode); status != StatusTimeLimitExceeded {
		t.Fatalf("expected TLE, got %s", status)
	}
	for _, code := range []string{"124", "125", "137"} {
		if status := ClassifyExit(run("raise SystemExit("+code+")", "", 1000).ExitCode); status != StatusRuntimeError {
			t.Fatalf("student exit %s should remain a runtime error, got %s", code, status)
		}
	}

	runner.ImageCPP = "kece-intentionally-missing-sandbox:local-test"
	runner.CompileTimeout = time.Second
	runner.CompileMemoryMb = 128
	if _, _, err := runner.Compile(context.Background(), "cpp", ws); err == nil {
		t.Fatal("missing sandbox image must be an infrastructure error")
	}
}
