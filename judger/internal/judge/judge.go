// Package judge 编排编译、逐用例运行与结果汇总，并回写 Redis 状态。
package judge

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/compare"
	"github.com/zheco68-collab/KECE/judger/internal/config"
	"github.com/zheco68-collab/KECE/judger/internal/model"
	"github.com/zheco68-collab/KECE/judger/internal/queue"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
)

const (
	statusCompiling = "COMPILING"
	statusJudging   = "JUDGING"
)

// Judge 为单次提交的判题编排器。
type Judge struct {
	queue  *queue.Queue
	runner *sandbox.Runner
	cfg    config.Config
}

// New 创建判题编排器。
func New(cfg config.Config, q *queue.Queue, r *sandbox.Runner) *Judge {
	return &Judge{queue: q, runner: r, cfg: cfg}
}

// ParseTask 解析后端推送的任务 JSON。
func ParseTask(data string) (model.Task, error) {
	var task model.Task
	err := json.Unmarshal([]byte(data), &task)
	return task, err
}

// Process 完整处理一次提交：编译 → 逐用例运行 → 汇总并回写终态。
func (j *Judge) Process(ctx context.Context, task model.Task) {
	ws, err := os.MkdirTemp(j.cfg.WorkspaceDir, "sub-")
	if err != nil {
		j.finishSystemError(ctx, task.SubmissionID)
		return
	}
	defer os.RemoveAll(ws)
	_ = os.Chmod(ws, 0o777)

	// 1. 落盘源码。
	filename := filenameFor(task.Language)
	if err := os.WriteFile(filepath.Join(ws, filename), []byte(extractCode(task, filename)), 0o666); err != nil {
		j.finishSystemError(ctx, task.SubmissionID)
		return
	}

	// 启动单次提交沙箱会话（复用容器，避免逐用例 cold start）
	sess, _ := j.runner.StartSession(ctx, task.Language, ws, task.MemoryLimitMb)
	if sess != nil {
		defer func() {
			closeCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = sess.Close(closeCtx)
		}()
	}

	// 2. 编译。
	j.publish(ctx, task.SubmissionID, statusCompiling, len(task.TestCases), 0)
	var (
		stderr string
		ok     bool
	)
	if sess != nil {
		stderr, ok, err = sess.Compile(ctx)
	} else {
		stderr, ok, err = j.runner.Compile(ctx, task.Language, ws)
	}
	if err != nil {
		j.finishSystemError(ctx, task.SubmissionID)
		return
	}
	if !ok {
		j.finishCompileError(ctx, task.SubmissionID, stderr)
		return
	}

	// 编译成功后，将会话容器内存上限收缩至题目的运行内存限制
	if sess != nil {
		_ = sess.ApplyRunMemoryLimit(ctx, task.MemoryLimitMb)
	}

	// 3. 逐用例运行并汇总。
	j.publish(ctx, task.SubmissionID, statusJudging, len(task.TestCases), 0)
	report := make([]model.CaseResult, 0, len(task.TestCases))
	statuses := make([]string, 0, len(task.TestCases))
	maxTimeMs := 0
	// 各用例峰值内存的最大值；全部用例都测不到时保持 nil，不伪造成 0。
	var maxMemKb *int
	for i, tc := range task.TestCases {
		cr, err := j.runCaseWithSession(ctx, task, ws, tc, sess)
		if err != nil {
			j.finishSystemError(ctx, task.SubmissionID)
			return
		}
		report = append(report, cr)
		statuses = append(statuses, cr.Status)
		if cr.TimeMs != nil && *cr.TimeMs > maxTimeMs {
			maxTimeMs = *cr.TimeMs
		}
		if cr.MemoryKb != nil && (maxMemKb == nil || *cr.MemoryKb > *maxMemKb) {
			kb := *cr.MemoryKb
			maxMemKb = &kb
		}
		j.publish(ctx, task.SubmissionID, statusJudging, len(task.TestCases), i+1)
	}

	// 4. 写终态并推送结果。
	overall := AggregateOverall(statuses)
	result := model.Result{
		Score:           intPtr(scoreFrom(statuses)),
		TimeUsedMs:      intPtr(maxTimeMs),
		MemoryUsedKb:    maxMemKb,
		TestCasesReport: report,
	}
	j.finishTerminal(ctx, task.SubmissionID, overall, result)
}

// runCase 运行单个测试点并返回其判题结果。
func (j *Judge) runCase(ctx context.Context, task model.Task, ws string, tc model.TestCase) (model.CaseResult, error) {
	return j.runCaseWithSession(ctx, task, ws, tc, nil)
}

// runCaseWithSession 在指定沙箱会话（若无则兜底新建容器）中运行单个测试点。
func (j *Judge) runCaseWithSession(ctx context.Context, task model.Task, ws string, tc model.TestCase, sess *sandbox.Session) (model.CaseResult, error) {
	targetInPath := filepath.Join(ws, "input.txt")
	var expectedOutput string

	// 1. 准备输入数据（优先读文件，若无或读取失败则降级为 tc.Input 字符串）
	if tc.InputPath != "" {
		srcIn := tc.InputPath
		if !filepath.IsAbs(srcIn) {
			srcIn = filepath.Join(j.cfg.TestcaseDir, tc.InputPath)
		}
		if data, err := os.ReadFile(srcIn); err == nil {
			if err := os.WriteFile(targetInPath, data, 0o666); err != nil {
				return model.CaseResult{}, err
			}
		} else if tc.Input != "" {
			_ = os.WriteFile(targetInPath, []byte(tc.Input), 0o666)
		} else {
			return model.CaseResult{}, fmt.Errorf("read input file %s: %w", srcIn, err)
		}
	} else {
		if err := os.WriteFile(targetInPath, []byte(tc.Input), 0o666); err != nil {
			return model.CaseResult{}, err
		}
	}

	// 2. 准备期望输出（优先读文件，若无或读取失败则降级为 tc.ExpectedOutput 字符串）
	if tc.OutputPath != "" {
		srcOut := tc.OutputPath
		if !filepath.IsAbs(srcOut) {
			srcOut = filepath.Join(j.cfg.TestcaseDir, tc.OutputPath)
		}
		if data, err := os.ReadFile(srcOut); err == nil {
			expectedOutput = string(data)
		} else {
			expectedOutput = tc.ExpectedOutput
		}
	} else {
		expectedOutput = tc.ExpectedOutput
	}

	var (
		res sandbox.RunResult
		err error
	)
	if sess != nil {
		res, err = sess.Run(ctx, tc.Input, task.TimeLimitMs)
	} else {
		res, err = j.runner.Run(ctx, task.Language, ws, tc.Input, task.TimeLimitMs, task.MemoryLimitMb)
	}
	if err != nil {
		return model.CaseResult{}, err
	}

	cr := model.CaseResult{
		CaseID:   tc.CaseID,
		TimeMs:   intPtr(int(res.Elapsed.Milliseconds())),
		MemoryKb: res.MemoryKb,
	}
	switch status := sandbox.ClassifyExit(res.ExitCode); status {
	case "":
		if compare.Equal(res.Stdout, expectedOutput) {
			cr.Status = sandbox.StatusAccepted
		} else {
			cr.Status = sandbox.StatusWrongAnswer
			cr.ExpectedOutput = strPtr(truncateStr(expectedOutput, 512))
			cr.ActualOutput = strPtr(truncateStr(res.Stdout, 512))
		}
	case sandbox.StatusMemoryLimitExceeded:
		cr.Status = status
		// 撞到上限时 cgroup 峰值通常已顶到限制值；探测不可用时退回按限制值上报。
		if cr.MemoryKb == nil {
			cr.MemoryKb = intPtr(task.MemoryLimitMb * 1024)
		}
	default:
		cr.Status = status
	}
	return cr, nil
}

// publish 写入进行中状态与进度。
func (j *Judge) publish(ctx context.Context, submissionID int64, status string, total, finished int) {
	vo := model.SubmissionVO{
		SubmissionID: submissionID,
		Status:       status,
		Progress:     &model.Progress{TotalCases: total, FinishedCases: finished},
	}
	j.writeStatus(ctx, submissionID, vo)
}

// finishCompileError 写入编译错误终态。
func (j *Judge) finishCompileError(ctx context.Context, submissionID int64, log string) {
	result := model.Result{Score: intPtr(0), CompileErrorLog: strPtr(log), TestCasesReport: []model.CaseResult{}}
	j.finishTerminal(ctx, submissionID, sandbox.StatusCompileError, result)
}

// finishSystemError 写入系统错误终态。
func (j *Judge) finishSystemError(ctx context.Context, submissionID int64) {
	result := model.Result{Score: intPtr(0), TestCasesReport: []model.CaseResult{}}
	j.finishTerminal(ctx, submissionID, sandbox.StatusSystemError, result)
}

// finishTerminal 写入终态并推送结果。
func (j *Judge) finishTerminal(ctx context.Context, submissionID int64, status string, result model.Result) {
	vo := model.SubmissionVO{SubmissionID: submissionID, Status: status, Result: &result}
	j.writeStatus(ctx, submissionID, vo)
	_ = j.queue.PushResult(ctx, submissionID)
}

// writeStatus 序列化并写状态缓存，序列化失败时静默忽略。
func (j *Judge) writeStatus(ctx context.Context, submissionID int64, vo model.SubmissionVO) {
	data, err := json.Marshal(vo)
	if err != nil {
		return
	}
	_ = j.queue.PublishStatus(ctx, submissionID, string(data))
}

// AggregateOverall 根据各用例状态汇总整体结论：取首个非 AC 状态，全 AC 则为 AC。
func AggregateOverall(statuses []string) string {
	for _, s := range statuses {
		if s != sandbox.StatusAccepted {
			return s
		}
	}
	return sandbox.StatusAccepted
}

// scoreFrom 按通过用例占比给分（满分 100，向下取整）；无用例时为 0。
func scoreFrom(statuses []string) int {
	if len(statuses) == 0 {
		return 0
	}
	accepted := 0
	for _, s := range statuses {
		if s == sandbox.StatusAccepted {
			accepted++
		}
	}
	return accepted * 100 / len(statuses)
}

// filenameFor 返回各语言约定的源码文件名。
func filenameFor(lang string) string {
	switch lang {
	case "cpp":
		return "main.cpp"
	case "java":
		return "Main.java"
	case "python":
		return "main.py"
	case "arkts":
		return "main.ets"
	default:
		return "main.txt"
	}
}

// extractCode 按约定文件名取源码，缺失时回退到任意非空片段。
func extractCode(task model.Task, filename string) string {
	if code, ok := task.CodeContent[filename]; ok && code != "" {
		return code
	}
	if filename == "main.ets" {
		if code, ok := task.CodeContent["main.ts"]; ok && code != "" {
			return code
		}
	}
	for _, code := range task.CodeContent {
		if code != "" {
			return code
		}
	}
	return ""
}

func intPtr(v int) *int       { return &v }
func strPtr(v string) *string { return &v }

func truncateStr(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "... [已截断，总长度: " + strconv.Itoa(len(s)) + " 字符]"
}
