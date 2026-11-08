// Package judge 编排编译、逐用例运行与结果汇总。
package judge

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"github.com/zheco68-collab/KECE/judger/internal/compare"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
)

const (
	statusCompiling = "COMPILING"
	statusJudging   = "JUDGING"
)

// CodeJudge runs code against a set of test cases.
type CodeJudge struct {
	runner      *sandbox.Runner
	testcaseDir string
}

func NewCode(runner *sandbox.Runner, testcaseDir string) *CodeJudge {
	return &CodeJudge{runner: runner, testcaseDir: testcaseDir}
}

// Execute compiles, runs test cases, and returns the verdict to its caller.
func (j *CodeJudge) Execute(ctx context.Context, task CodeTask, progress ProgressSink) (Verdict, error) {
	ws, err := os.MkdirTemp(j.runner.Workspace, "sub-")
	if err != nil {
		return Verdict{}, err
	}
	defer os.RemoveAll(ws)
	_ = os.Chmod(ws, 0o777)

	// 1. 落盘源码。
	filename := filenameFor(task.Language)
	if err := os.WriteFile(filepath.Join(ws, filename), []byte(extractCode(task, filename)), 0o666); err != nil {
		return Verdict{}, err
	}

	// 2. 编译（在独立编译容器中进行，避免编译器 g++/javac/tsc 的内存峰值污染运行容器的 cgroup memory.peak）。
	progress.update(statusCompiling, len(task.TestCases), 0)
	stderr, ok, err := j.runner.Compile(ctx, task.Language, ws)
	if err != nil {
		return Verdict{}, err
	}
	if !ok {
		return Verdict{Status: sandbox.StatusCompileError, Score: intPtr(0), CompileErrorLog: strPtr(stderr), TestCasesReport: []CaseResult{}}, nil
	}

	// 3. 逐用例运行并汇总。
	progress.update(statusJudging, len(task.TestCases), 0)
	report := make([]CaseResult, 0, len(task.TestCases))
	statuses := make([]string, 0, len(task.TestCases))
	maxTimeMs := 0
	// 各用例峰值内存的最大值；全部用例都测不到时保持 nil，不伪造成 0。
	var maxMemKb *int
	for i, tc := range task.TestCases {
		cr, err := j.runCase(ctx, task, ws, tc)
		if err != nil {
			return Verdict{}, err
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
		progress.update(statusJudging, len(task.TestCases), i+1)
	}

	// 4. 汇总终态。
	return Verdict{
		Status:          AggregateOverall(statuses),
		Score:           intPtr(scoreFromCases(task.TestCases, statuses)),
		TimeUsedMs:      intPtr(maxTimeMs),
		MemoryUsedKb:    maxMemKb,
		TestCasesReport: report,
	}, nil
}

func (j *CodeJudge) runCase(ctx context.Context, task CodeTask, ws string, tc TestCase) (CaseResult, error) {
	input := []byte(tc.Input)
	var expectedOutput string
	if task.RequireCaseFiles && (tc.InputPath == "" || tc.OutputPath == "") {
		return CaseResult{}, fmt.Errorf("verified case files are missing")
	}

	// 输入通过标准输入传入，避免写入由学生代码控制的目录。
	if tc.InputPath != "" {
		srcIn := tc.InputPath
		if !filepath.IsAbs(srcIn) {
			srcIn = filepath.Join(j.testcaseDir, tc.InputPath)
		}
		if data, err := os.ReadFile(srcIn); err == nil {
			input = data
		} else if task.RequireCaseFiles || tc.Input == "" {
			return CaseResult{}, fmt.Errorf("read input file %s: %w", srcIn, err)
		}
	}

	// 2. 准备期望输出（优先读文件，若无或读取失败则降级为 tc.ExpectedOutput 字符串）
	if tc.OutputPath != "" {
		srcOut := tc.OutputPath
		if !filepath.IsAbs(srcOut) {
			srcOut = filepath.Join(j.testcaseDir, tc.OutputPath)
		}
		if data, err := os.ReadFile(srcOut); err == nil {
			expectedOutput = string(data)
		} else if task.RequireCaseFiles {
			return CaseResult{}, fmt.Errorf("read verified output: %w", err)
		} else {
			expectedOutput = tc.ExpectedOutput
		}
	} else {
		expectedOutput = tc.ExpectedOutput
	}

	res, err := j.runner.Run(ctx, task.Language, ws, task.TimeLimitMs, task.MemoryLimitMb, bytes.NewReader(input))
	if err != nil {
		return CaseResult{}, err
	}

	cr := CaseResult{
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

// AggregateOverall 根据各用例状态汇总整体结论：取首个非 AC 状态，全 AC 则为 AC。
func AggregateOverall(statuses []string) string {
	for _, s := range statuses {
		if s != sandbox.StatusAccepted {
			return s
		}
	}
	return sandbox.StatusAccepted
}

// scoreFromCases 优先按各用例 ScoreWeight 权重占比计分（满分 100，向下取整）；若未配置正权重则退化为均分。
func scoreFromCases(cases []TestCase, statuses []string) int {
	if len(statuses) == 0 {
		return 0
	}
	if len(cases) == len(statuses) {
		totalWeight := 0
		acceptedWeight := 0
		for i, tc := range cases {
			if tc.ScoreWeight > 0 {
				totalWeight += tc.ScoreWeight
				if statuses[i] == sandbox.StatusAccepted {
					acceptedWeight += tc.ScoreWeight
				}
			}
		}
		if totalWeight > 0 {
			if acceptedWeight == totalWeight {
				return 100
			}
			return acceptedWeight * 100 / totalWeight
		}
	}
	return scoreFrom(statuses)
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
func extractCode(task CodeTask, filename string) string {
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
