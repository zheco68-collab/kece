// Package sandbox 基于 Docker CLI 提供受限的编译与运行执行环境。
package sandbox

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const (
	backstopBuffer = 30 * time.Second
	maxOutputBytes = 64 << 10
	maxStderrBytes = 8 << 10
	memProbePrefix = "kece.memory_bytes="
	runTimePrefix  = "kece.run_ms="
)

// limitedBuffer 在采集阶段即限制最大缓冲字节数，避免超大标准输出撑爆 worker 内存。
type limitedBuffer struct {
	buf []byte
	max int
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	origLen := len(p)
	if len(b.buf) < b.max {
		remain := b.max - len(b.buf)
		if len(p) > remain {
			p = p[:remain]
		}
		b.buf = append(b.buf, p...)
	}
	return origLen, nil
}

func (b *limitedBuffer) String() string {
	return string(b.buf)
}

func newContainerName(prefix string) string {
	var rnd [6]byte
	_, _ = rand.Read(rnd[:])
	return fmt.Sprintf("kece-%s-%d-%s", prefix, time.Now().Unix(), hex.EncodeToString(rnd[:]))
}

func forceRemoveContainer(name string) {
	if name == "" {
		return
	}
	rmCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _, _, _ = runDocker(rmCtx, []string{"rm", "-f", name})
}

// Runner 通过宿主机 Docker daemon 执行沙箱容器。
type Runner struct {
	Workspace       string
	CgroupParent    string
	CompileTimeout  time.Duration
	CompileMemoryMb int
	ImageCPP        string
	ImageJava       string
	ImagePython     string
	ImageArkTS      string
}

// RunResult 为单次运行的原始结果。
type RunResult struct {
	Stdout   string
	ExitCode int
	Elapsed  time.Duration
	// MemoryKb 为本次运行的峰值内存（含运行时基线）；宿主机/内核不支持探测时为 nil。
	MemoryKb *int
}

// Compile 编译源码；Python 等解释型语言直接返回成功。
// stderr 为编译日志，ok 表示编译是否成功。
func (r *Runner) Compile(ctx context.Context, lang, ws string) (stderr string, ok bool, err error) {
	shell, need := compileCommand(lang)
	if !need {
		return "", true, nil
	}
	timeoutCtx, cancel := context.WithTimeout(ctx, r.CompileTimeout+backstopBuffer)
	defer cancel()

	cname := newContainerName("cmp")
	seconds := fmt.Sprintf("%.3f", r.CompileTimeout.Seconds())
	shell = fmt.Sprintf("timeout -s KILL %s sh -c '%s'; code=$?; %s", seconds, shell, normalizeExit)
	args := r.dockerArgs(ctx, cname, r.CompileMemoryMb, ws, r.imageFor(lang), shell, false)
	stdout, stderr, code, err := runDocker(timeoutCtx, args)
	if err != nil {
		forceRemoveContainer(cname)
		if ctx.Err() != nil {
			return "", false, ctx.Err()
		}
		return "", false, fmt.Errorf("docker compile: %w", err)
	}
	if code == exitTLE || code == exitOOM {
		return "compile timed out", false, nil
	}
	compileLog := stderr
	if compileLog == "" {
		compileLog = stdout
	}
	return truncate(compileLog, maxStderrBytes), code == 0, nil
}

// Run 在独立容器中运行一个测试用例，直接通过标准输入传输用例数据。
func (r *Runner) Run(ctx context.Context, lang, ws string, timeLimitMs, memoryMb int, input io.Reader) (RunResult, error) {
	res := RunResult{}
	sec := fmt.Sprintf("%.3f", float64(timeLimitMs)/1000.0)
	shell := runCommand(lang, sec)

	timeoutCtx, cancel := context.WithTimeout(ctx, time.Duration(timeLimitMs)*time.Millisecond+backstopBuffer)
	defer cancel()

	cname := newContainerName("run")
	start := time.Now()
	args := r.dockerArgs(ctx, cname, memoryMb, ws, r.imageFor(lang), shell, true)
	stdout, stderr, code, err := runDockerWithInput(timeoutCtx, args, input)
	res.Elapsed = time.Since(start)
	res.Stdout = truncate(stdout, maxOutputBytes)
	res.ExitCode = code
	if err != nil {
		forceRemoveContainer(cname)
		if ctx.Err() != nil {
			return res, ctx.Err()
		}
		return res, fmt.Errorf("docker run: %w", err)
	}
	res.MemoryKb = readMemProbe(stderr)
	if measured, err := strconv.Atoi(readProbe(stderr, runTimePrefix)); err == nil && measured >= 0 {
		res.Elapsed = time.Duration(measured) * time.Millisecond
	}
	return res, nil
}

// dockerArgs 构造沙箱容器启动参数，落实网络隔离、Cgroups 资源限制与权限收敛。
func (r *Runner) dockerArgs(ctx context.Context, containerName string, memoryMb int, ws, image, shellCmd string, stdin bool) []string {
	args := []string{"run", "--rm", "--pull=never", "--name", containerName, "--label", "kece.managed=true"}
	mount := ws + ":/work"
	if stdin {
		// The supervisor needs to switch user and signal the resulting process.
		// The student runs as nobody without these capabilities.
		args = append(args, "-i")
		mount += ":ro"
	}
	if owner, ok := ctx.Value(ownerKey{}).(containerOwner); ok {
		args = append(args, "--label", "kece.node="+owner.nodeID,
			"--label", "kece.job="+owner.jobID,
			"--label", "kece.session="+strconv.FormatInt(owner.sessionEpoch, 10),
			"--label", "kece.lease="+strconv.FormatInt(owner.leaseEpoch, 10))
	}
	args = append(args,
		"--network", "none",
		"--memory", fmt.Sprintf("%dm", memoryMb),
		"--memory-swap", fmt.Sprintf("%dm", memoryMb),
		"--pids-limit", "64",
		"--cpus", "1",
		"--cap-drop", "ALL",
		"--security-opt", "no-new-privileges",
	)
	if stdin {
		args = append(args, "--cap-add", "SETUID", "--cap-add", "SETGID", "--cap-add", "KILL")
	}
	if r.CgroupParent != "" {
		args = append(args, "--cgroup-parent", r.CgroupParent)
	}
	args = append(args,
		"-v", mount,
		"-w", "/work",
		image,
		"sh", "-c", shellCmd,
	)
	return args
}

func (r *Runner) imageFor(lang string) string {
	switch lang {
	case "cpp":
		return r.ImageCPP
	case "java":
		return r.ImageJava
	case "python":
		return r.ImagePython
	case "arkts":
		return r.ImageArkTS
	default:
		return ""
	}
}

func compileCommand(lang string) (string, bool) {
	switch lang {
	case "cpp":
		return "g++ -O2 -std=c++17 -o main main.cpp", true
	case "java":
		return "javac Main.java", true
	case "arkts":
		return "cp main.ets main.ts 2>/dev/null || true; tsc --target ES2022 --module CommonJS --strict --skipLibCheck --typeRoots /usr/local/lib/node_modules/@types --types node main.ts", true
	default:
		return "", false
	}
}

// Docker CLI reserves 125–127 for launch errors. Keep user exit codes out of that range.
const normalizeExit = "case $code in 125|126|127) code=1;; esac; exit $code"

// runCommand 构造容器内的运行命令。
//
// The supervisor owns the timeout and the metrics channel. The student can only
// write to stdout and cannot signal the supervisor or report fabricated metrics.
func runCommand(lang, sec string) string {
	var run string
	switch lang {
	case "cpp":
		run = "./main"
	case "java":
		run = "java -cp /work Main"
	case "python":
		run = "python3 /work/main.py"
	case "arkts":
		run = "node /work/main.js"
	default:
		return "exit 1"
	}
	return fmt.Sprintf(
		"read -r start _ < /proc/uptime; "+
			"KECE_RUNTIME_PATH=\"$PATH\" timeout -s KILL %s su -p -s /bin/sh nobody -c 'export PATH=$KECE_RUNTIME_PATH HOME=/tmp; %s; code=$?; case $code in 124|125|126|127|137) code=1;; esac; exit $code' 2>/dev/null; code=$?; "+
			"read -r finish _ < /proc/uptime; "+
			"awk -v start=\"$start\" -v finish=\"$finish\" 'BEGIN {if (start != \"\" && finish != \"\") printf \"%s%%.0f\\n\", (finish-start)*1000}' >&2; "+
			"if [ -r /sys/fs/cgroup/memory.peak ]; then printf '%s' >&2; cat /sys/fs/cgroup/memory.peak >&2; fi; "+
			"if grep -Eq '^oom_kill [1-9][0-9]*$' /sys/fs/cgroup/memory.events 2>/dev/null; then code=137; "+
			"elif [ \"$code\" -eq 137 ]; then code=124; fi; %s",
		sec, run, runTimePrefix, memProbePrefix, normalizeExit)
}

// readMemProbe reads the cgroup measurement from the supervisor's stderr.
func readMemProbe(stderr string) *int {
	bytesVal, err := strconv.ParseInt(readProbe(stderr, memProbePrefix), 10, 64)
	if err != nil || bytesVal <= 0 {
		return nil
	}
	kb := int(bytesVal / 1024)
	if kb <= 0 {
		return nil
	}
	return &kb
}

func readProbe(stderr, prefix string) string {
	for _, line := range strings.Split(stderr, "\n") {
		if value, found := strings.CutPrefix(line, prefix); found {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

// runDocker 执行 docker 子命令并返回受限长度的输出与退出码。
// 仅在 docker 命令本身失败（如 daemon 不可用或 context 取消）时返回错误；容器非零退出不算错误。
func runDocker(ctx context.Context, args []string) (stdout, stderr string, exitCode int, err error) {
	return runDockerWithInput(ctx, args, nil)
}

func runDockerWithInput(ctx context.Context, args []string, input io.Reader) (stdout, stderr string, exitCode int, err error) {
	cmd := exec.CommandContext(ctx, "docker", args...)
	cmd.Stdin = input
	outBuf := &limitedBuffer{max: maxOutputBytes}
	errBuf := &limitedBuffer{max: maxStderrBytes}
	cmd.Stdout = outBuf
	cmd.Stderr = errBuf
	err = cmd.Run()
	stdout = outBuf.String()
	stderr = errBuf.String()
	if err != nil {
		if ctx.Err() != nil {
			return stdout, stderr, -1, ctx.Err()
		}
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) {
			code := exitErr.ExitCode()
			if args[0] == "run" && code >= 125 && code <= 127 {
				return stdout, stderr, code, fmt.Errorf("Docker 启动失败（%d）: %s", code, truncate(strings.TrimSpace(stderr), 500))
			}
			return stdout, stderr, code, nil
		}
		return stdout, stderr, -1, err
	}
	return stdout, stderr, 0, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// PrewarmPool 仅预热当前专池所需的单个沙箱镜像，不检查或拉取其他语言的镜像。
func (r *Runner) PrewarmPool(ctx context.Context, pool string) {
	img := r.imageFor(pool)
	if img == "" {
		return
	}
	go func(image string) {
		timeoutCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		_, _, _, _ = runDocker(timeoutCtx, []string{"run", "--rm", image, "true"})
	}(img)
}
