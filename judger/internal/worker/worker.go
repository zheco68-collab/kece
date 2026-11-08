package worker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/artifact"
	"github.com/zheco68-collab/KECE/judger/internal/control"
	"github.com/zheco68-collab/KECE/judger/internal/health"
	"github.com/zheco68-collab/KECE/judger/internal/judge"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
)

const maxClaimSlots = 16

type Config struct {
	Pool              string
	EnrollmentCode    string
	CredentialFile    string
	BootID            string
	RuntimeSpec       string
	RuntimeVersion    string
	LocalSlots        int
	ClaimWaitMs       int
	HeartbeatInterval time.Duration
	RenewInterval     time.Duration
	SafetyMargin      time.Duration
	DrainTimeout      time.Duration
	HealthCheck       func(context.Context) error
}

type StoredCredential struct {
	NodeID     string `json:"nodeId"`
	Credential string `json:"credential"`
}

type Executor interface {
	Execute(ctx context.Context, task judge.CodeTask, progress judge.ProgressSink) (judge.Verdict, error)
}

type SQLExecutor interface {
	Execute(ctx context.Context, task judge.SQLTask, progress judge.ProgressSink) (judge.Verdict, error)
}

type Worker struct {
	cfg         Config
	client      *control.Client
	cache       *artifact.Cache
	executor    Executor
	sqlExecutor SQLExecutor
	runner      *sandbox.Runner

	state    string
	maxSlots int
	stateMu  sync.RWMutex

	activeCount    atomic.Int32
	healthy        atomic.Bool
	cleanupPending bool
	lastCheck      *control.CheckReport
	lastCheckID    string
	checking       bool
	checkMu        sync.Mutex
	checkReady     chan struct{}
	checkTasks     sync.WaitGroup
}

func NewWorker(cfg Config, client *control.Client, cache *artifact.Cache, exec Executor, sqlExec SQLExecutor, runner *sandbox.Runner) *Worker {
	if cfg.LocalSlots <= 0 {
		cfg.LocalSlots = 1
	}
	if cfg.ClaimWaitMs <= 0 {
		cfg.ClaimWaitMs = 15000
	}
	if cfg.HeartbeatInterval <= 0 {
		cfg.HeartbeatInterval = 10 * time.Second
	}
	if cfg.RenewInterval <= 0 {
		cfg.RenewInterval = 15 * time.Second
	}
	if cfg.SafetyMargin <= 0 {
		cfg.SafetyMargin = 5 * time.Second
	}
	if cfg.DrainTimeout <= 0 {
		cfg.DrainTimeout = 30 * time.Second
	}

	return &Worker{
		cfg:            cfg,
		client:         client,
		cache:          cache,
		executor:       exec,
		sqlExecutor:    sqlExec,
		runner:         runner,
		cleanupPending: runner != nil,
		checkReady:     make(chan struct{}, 1),
	}
}

// HealthSnapshot 返回节点当前只读状态快照，供内网 /healthz 探测使用。
func (w *Worker) HealthSnapshot() health.Status {
	w.stateMu.RLock()
	st := w.state
	maxS := w.maxSlots
	w.stateMu.RUnlock()
	if st == "" {
		st = "PENDING_VERIFICATION"
	}
	return health.Status{
		ProtocolVersion: 1,
		NodeID:          w.client.NodeID(),
		Pool:            w.cfg.Pool,
		RuntimeSpec:     w.cfg.RuntimeSpec,
		RuntimeVersion:  w.cfg.RuntimeVersion,
		State:           st,
		Healthy:         w.client.Epoch() > 0 && w.healthy.Load(),
		ActiveSlots:     int(w.activeCount.Load()),
		LocalSlots:      w.cfg.LocalSlots,
		MaxSlots:        maxS,
	}
}

func (w *Worker) Authenticate(ctx context.Context) error {
	var cred StoredCredential
	hasCred := false

	if w.cfg.CredentialFile != "" {
		if data, err := os.ReadFile(w.cfg.CredentialFile); err == nil {
			if err := json.Unmarshal(data, &cred); err == nil && cred.NodeID != "" && cred.Credential != "" {
				hasCred = true
			}
		}
	}

	if hasCred {
		w.client.SetCredentials(cred.NodeID, cred.Credential, 0)
		sess, err := w.client.Session(ctx, w.cfg.BootID)
		if err == nil {
			log.Printf("[worker] resumed session for node %s (epoch %d)", sess.NodeID, sess.SessionEpoch)
			return nil
		}
		log.Printf("[worker] session resumption failed: %v, attempting re-join if enrollment code present", err)
	}

	if w.cfg.EnrollmentCode == "" {
		return fmt.Errorf("no valid credentials in %s and no enrollment code provided", w.cfg.CredentialFile)
	}

	joined, err := w.client.Join(ctx, w.cfg.EnrollmentCode, w.cfg.BootID)
	if err != nil {
		return fmt.Errorf("join failed: %w", err)
	}

	log.Printf("[worker] joined as node %s (epoch %d, pool %s)", joined.NodeID, joined.SessionEpoch, joined.Pool)

	if w.cfg.CredentialFile != "" {
		if dir := filepath.Dir(w.cfg.CredentialFile); dir != "" {
			_ = os.MkdirAll(dir, 0o700)
		}
		data, _ := json.MarshalIndent(StoredCredential{
			NodeID:     joined.NodeID,
			Credential: joined.Credential,
		}, "", "  ")
		_ = os.WriteFile(w.cfg.CredentialFile, data, 0o600)
	}

	return nil
}

func (w *Worker) Run(ctx context.Context) error {
	if err := w.Authenticate(ctx); err != nil {
		return err
	}

	claimCtx, stopClaims := context.WithCancel(ctx)
	defer stopClaims()
	jobParent, stopJobs := context.WithCancel(context.WithoutCancel(ctx))
	defer stopJobs()

	w.probeDependencies(claimCtx)
	if err := w.doHeartbeat(claimCtx, claimCtx); err != nil {
		return fmt.Errorf("initial heartbeat: %w", err)
	}

	hbDone := make(chan struct{})
	hbErrors := make(chan error, 1)
	go func() {
		defer close(hbDone)
		if err := w.heartbeatLoop(jobParent, claimCtx); err != nil {
			hbErrors <- err
			stopClaims()
			stopJobs()
		}
	}()
	defer func() {
		stopJobs()
		<-hbDone
		w.checkTasks.Wait()
	}()

	var jobs sync.WaitGroup
	claimErr := w.claimLoop(claimCtx, jobParent, &jobs)
	if claimErr != nil {
		stopJobs()
	}
	if err := waitForJobs(&jobs, stopJobs, w.cfg.DrainTimeout); err != nil {
		return err
	}
	select {
	case err := <-hbErrors:
		return err
	default:
		return claimErr
	}
}

func waitForJobs(jobs *sync.WaitGroup, stopJobs context.CancelFunc, grace time.Duration) error {
	done := make(chan struct{})
	go func() {
		jobs.Wait()
		close(done)
	}()

	timer := time.NewTimer(grace)
	defer timer.Stop()
	select {
	case <-done:
		return nil
	case <-timer.C:
		log.Print("[worker] drain grace period elapsed, canceling remaining jobs")
		stopJobs()
	}

	cleanup := time.NewTimer(5 * time.Second)
	defer cleanup.Stop()
	select {
	case <-done:
		return nil
	case <-cleanup.C:
		return errors.New("timed out waiting for canceled jobs to clean up")
	}
}

func (w *Worker) claimLoop(claimCtx, jobParent context.Context, jobs *sync.WaitGroup) error {
	claimTicker := time.NewTicker(200 * time.Millisecond)
	defer claimTicker.Stop()

	for {
		select {
		case <-claimCtx.Done():
			return nil
		case <-claimTicker.C:
		}
		w.stateMu.RLock()
		state := w.state
		maxSlots := w.maxSlots
		w.stateMu.RUnlock()

		if state != "ENABLED" || !w.healthy.Load() || claimCtx.Err() != nil {
			continue
		}

		running := int(w.activeCount.Load())
		available := min(w.cfg.LocalSlots, maxSlots) - running
		if available <= 0 {
			continue
		}

		claimedAt := time.Now()
		batch, err := w.client.Claim(claimCtx, min(available, maxClaimSlots), w.cfg.ClaimWaitMs)
		if err != nil {
			if errors.Is(err, control.ErrConflict) || errors.Is(err, control.ErrUnauthorized) {
				return fmt.Errorf("claim session: %w", err)
			}
			if claimCtx.Err() == nil {
				log.Printf("[worker] claim error: %v", err)
			}
			continue
		}
		for _, job := range batch.Jobs {
			w.activeCount.Add(1)
			jobs.Add(1)
			go func(job control.ClaimedJob) {
				defer jobs.Done()
				defer w.activeCount.Add(-1)
				w.executeJob(jobParent, job, claimedAt)
			}(job)
		}
	}
}

func (w *Worker) heartbeatLoop(ctx, checkCtx context.Context) error {
	ticker := time.NewTicker(w.cfg.HeartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			w.probeDependencies(ctx)
		case <-w.checkReady:
		}

		if err := w.doHeartbeat(ctx, checkCtx); errors.Is(err, control.ErrUnauthorized) || errors.Is(err, control.ErrConflict) {
			return fmt.Errorf("heartbeat session: %w", err)
		}
	}
}

func (w *Worker) probeDependencies(ctx context.Context) {
	probeCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	if w.cfg.HealthCheck != nil {
		if err := w.cfg.HealthCheck(probeCtx); err != nil {
			w.healthy.Store(false)
			log.Printf("[worker] dependency unavailable: %v", err)
			return
		}
	}
	if w.cleanupPending {
		if err := w.runner.CleanupPreviousContainers(probeCtx, w.client.NodeID(), w.client.Epoch()); err != nil {
			w.healthy.Store(false)
			log.Printf("[worker] previous sandbox cleanup failed: %v", err)
			return
		}
		w.cleanupPending = false
	}
	w.healthy.Store(true)
}

func (w *Worker) doHeartbeat(ctx, checkCtx context.Context) error {
	w.checkMu.Lock()
	report := w.lastCheck
	w.checkMu.Unlock()

	hb, err := w.client.Heartbeat(ctx, control.HeartbeatRequest{
		RuntimeSpec:    w.cfg.RuntimeSpec,
		RuntimeVersion: w.cfg.RuntimeVersion,
		LocalSlots:     w.cfg.LocalSlots,
		Healthy:        w.healthy.Load(),
		CheckResult:    report,
	})
	if err != nil {
		var protocolError *control.ProtocolError
		if report != nil && errors.As(err, &protocolError) && protocolError.Code == "STALE_CHECK" {
			// The report was already committed or its check expired. Resume normal heartbeats.
			w.clearCheckReport(report)
			return nil
		}
		if ctx.Err() == nil {
			log.Printf("[worker] heartbeat error: %v", err)
		}
		return err
	}
	w.clearCheckReport(report)

	w.stateMu.Lock()
	w.state = hb.State
	w.maxSlots = hb.MaxSlots
	w.stateMu.Unlock()

	if hb.Check != nil {
		w.startSelfTest(checkCtx, *hb.Check)
	}

	return nil
}

func (w *Worker) clearCheckReport(report *control.CheckReport) {
	w.checkMu.Lock()
	defer w.checkMu.Unlock()
	if w.lastCheck == report {
		w.lastCheck = nil
	}
}

func (w *Worker) startSelfTest(parent context.Context, directive control.CheckDirective) {
	w.checkMu.Lock()
	defer w.checkMu.Unlock()
	if parent.Err() != nil || w.checking || w.lastCheck != nil || directive.CheckID == w.lastCheckID || directive.RemainingMs <= 0 {
		return
	}
	w.lastCheckID = directive.CheckID
	w.checking = true
	w.checkTasks.Add(1)

	go func() {
		defer w.checkTasks.Done()
		limit := min(15*time.Second, time.Duration(directive.RemainingMs)*time.Millisecond)
		ctx, cancel := context.WithTimeout(parent, limit)
		defer cancel()
		ctx = sandbox.WithOwner(ctx, w.client.NodeID(), "check-"+directive.CheckID, w.client.Epoch(), 0)
		detail := "ok"
		err := w.runSelfTest(ctx)
		if parent.Err() != nil {
			return
		}
		if err != nil {
			// Bound the UTF-16 length of the server's check detail field to 500.
			errorText := err.Error()
			detail = *boundTextPtr(&errorText, 250)
		}

		w.checkMu.Lock()
		w.checking = false
		w.lastCheck = &control.CheckReport{CheckID: directive.CheckID, Passed: err == nil, Detail: detail}
		w.checkMu.Unlock()
		select {
		case w.checkReady <- struct{}{}:
		default:
		}
	}()
}

func (w *Worker) runSelfTest(ctx context.Context) error {
	progress := func(string, int, int) {}
	if w.cfg.Pool == "mysql" {
		if w.sqlExecutor == nil {
			return errors.New("SQL executor is unavailable")
		}
		result, err := w.sqlExecutor.Execute(ctx, judge.SQLTask{
			Answer:       "SELECT 1",
			ReferenceSql: "SELECT 1",
			TimeLimitMs:  3000,
			TestCases:    []judge.TestCase{{CaseID: 1, ScoreWeight: 100}},
		}, progress)
		if err != nil {
			return err
		}
		if result.Status != "ACCEPTED" {
			return fmt.Errorf("MySQL self-test verdict: %s", result.Status)
		}
		return nil
	}

	if w.executor == nil {
		return errors.New("code executor is unavailable")
	}
	var filename, source string
	switch w.cfg.Pool {
	case "python":
		filename, source = "main.py", "print(input())\n"
	case "cpp":
		filename = "main.cpp"
		source = "#include <iostream>\n#include <string>\nint main() { std::string input; std::getline(std::cin, input); std::cout << input << '\\n'; }\n"
	case "java":
		filename = "Main.java"
		source = "public class Main { public static void main(String[] args) { System.out.println(new java.util.Scanner(System.in).nextLine()); } }\n"
	case "arkts":
		filename = "main.ets"
		source = "import { readFileSync } from 'node:fs';\nconsole.log(readFileSync(0, 'utf8').trim());\n"
	default:
		return fmt.Errorf("unsupported self-test pool %s", w.cfg.Pool)
	}
	result, err := w.executor.Execute(ctx, judge.CodeTask{
		Language:      w.cfg.Pool,
		CodeContent:   map[string]string{filename: source},
		TimeLimitMs:   3000,
		MemoryLimitMb: 256,
		TestCases: []judge.TestCase{{
			CaseID: 1, ScoreWeight: 100, Input: "selftest_ok\n", ExpectedOutput: "selftest_ok\n",
		}},
	}, progress)
	if err != nil {
		return err
	}
	if result.Status != "ACCEPTED" {
		return fmt.Errorf("%s self-test verdict: %s", w.cfg.Pool, result.Status)
	}
	return nil
}

func (w *Worker) executeJob(parentCtx context.Context, job control.ClaimedJob, claimedAt time.Time) {
	jobIDInt, _ := strconv.ParseInt(job.JobID, 10, 64)
	log.Printf("[worker] starting job %s (sub %s/%s, epoch %d, remaining %dms)",
		job.JobID, job.SubmissionType, job.SubmissionID, job.LeaseEpoch, job.LeaseRemainingMs)

	parentCtx = sandbox.WithOwner(parentCtx, w.client.NodeID(), job.JobID, w.client.Epoch(), job.LeaseEpoch)
	jobCtx, cancelJob := context.WithCancel(parentCtx)

	// Measure the lease from request start. Response latency never grants extra execution time.
	leaseDeadline := claimedAt.Add(time.Duration(job.LeaseRemainingMs)*time.Millisecond - w.cfg.SafetyMargin)
	if !time.Now().Before(leaseDeadline) {
		cancelJob()
		return
	}
	var leaseMu sync.Mutex
	expiry := time.AfterFunc(time.Until(leaseDeadline), func() {
		leaseMu.Lock()
		defer leaseMu.Unlock()
		if !time.Now().Before(leaseDeadline) {
			cancelJob()
		}
	})

	var currentPhase atomic.Pointer[string]
	initialPhase := "COMPILING"
	if job.SubmissionType == "DATABASE" {
		initialPhase = "JUDGING"
	}
	currentPhase.Store(&initialPhase)

	var finishedCount atomic.Int32
	renewDone := make(chan struct{})
	defer func() {
		cancelJob()
		<-renewDone
		expiry.Stop()
	}()
	go func() {
		defer close(renewDone)
		ticker := time.NewTicker(w.cfg.RenewInterval)
		defer ticker.Stop()
		var sequence int64
		for {
			select {
			case <-jobCtx.Done():
				return
			case <-ticker.C:
			}
			sequence++
			progress := control.Progress{
				JobID:         job.JobID,
				LeaseEpoch:    job.LeaseEpoch,
				Sequence:      sequence,
				Phase:         *currentPhase.Load(),
				FinishedCases: int(finishedCount.Load()),
			}
			renewedAt := time.Now()
			results, err := w.client.Renew(jobCtx, []control.Progress{progress})
			if err != nil {
				if jobCtx.Err() != nil {
					return
				}
				log.Printf("[worker] renew failed for job %s: %v", job.JobID, err)
				if errors.Is(err, control.ErrConflict) || errors.Is(err, control.ErrUnauthorized) {
					cancelJob()
					return
				}
				continue
			}
			if len(results) != 1 || results[0].JobID != job.JobID {
				log.Printf("[worker] renew returned an unexpected job for %s", job.JobID)
				continue
			}
			renewed := results[0]
			if renewed.Status != "RENEWED" {
				log.Printf("[worker] renew rejected for job %s: status=%s", job.JobID, renewed.Status)
				cancelJob()
				return
			}

			nextDeadline := renewedAt.Add(time.Duration(renewed.LeaseRemainingMs)*time.Millisecond - w.cfg.SafetyMargin)
			leaseMu.Lock()
			now := time.Now()
			if jobCtx.Err() != nil || !now.Before(leaseDeadline) || !now.Before(nextDeadline) {
				cancelJob()
				leaseMu.Unlock()
				return
			}
			leaseDeadline = nextDeadline
			expiry.Reset(nextDeadline.Sub(now))
			leaseMu.Unlock()
		}
	}()

	post := func(status string, result *control.SubmissionResultVO, errorCode string) {
		if jobCtx.Err() == nil {
			// Keep result retries independent of lease renewal. A lost ACK can be
			// retrieved idempotently even if the committed result ended the lease.
			w.postResult(parentCtx, job, status, result, errorCode)
		}
	}

	// Prepare artifacts
	fetch, err := artifact.LeaseSource(w.client.HTTPClient(), w.client.BaseURL(), jobIDInt, job.ArtifactToken)
	if err != nil {
		log.Printf("[worker] create lease source error for job %s: %v", job.JobID, err)
		post("", nil, "ARTIFACT_SOURCE_INVALID")
		return
	}

	prepared, err := w.cache.Prepare(jobCtx, job.Manifest, job.Source, fetch)
	if err != nil {
		log.Printf("[worker] prepare artifacts error for job %s: %v", job.JobID, err)
		post("", nil, "ARTIFACT_PREPARE_FAILED")
		return
	}
	defer prepared.Close()

	// Progress sink
	sink := func(phase string, total, finished int) {
		currentPhase.Store(&phase)
		finishedCount.Store(int32(finished))
	}

	// Execute
	var verdict judge.Verdict
	var execErr error
	if job.SubmissionType == "DATABASE" || w.cfg.Pool == "mysql" {
		if w.sqlExecutor == nil {
			log.Printf("[worker] no SQL executor configured for database job %s", job.JobID)
			post("", nil, "EXECUTOR_NOT_CONFIGURED")
			return
		}
		if prepared.SQL == nil {
			log.Printf("[worker] prepared SQL task is nil for job %s", job.JobID)
			post("", nil, "INVALID_TASK_MANIFEST")
			return
		}
		verdict, execErr = w.sqlExecutor.Execute(jobCtx, *prepared.SQL, sink)
	} else {
		if w.executor == nil {
			log.Printf("[worker] no code executor configured for job %s", job.JobID)
			post("", nil, "EXECUTOR_NOT_CONFIGURED")
			return
		}
		if prepared.Code == nil {
			log.Printf("[worker] prepared Code task is nil for job %s", job.JobID)
			post("", nil, "INVALID_TASK_MANIFEST")
			return
		}
		verdict, execErr = w.executor.Execute(jobCtx, *prepared.Code, sink)
	}

	if execErr != nil {
		log.Printf("[worker] execution failed for job %s: %v", job.JobID, execErr)
		post("", nil, "EXECUTION_FAILED")
		return
	}

	// Map verdict (initialize TestCasesReport as non-nil slice so COMPILE_ERROR marshals as [] rather than null)
	resultVO := &control.SubmissionResultVO{
		Score:           verdict.Score,
		TimeUsedMs:      verdict.TimeUsedMs,
		MemoryUsedKb:    verdict.MemoryUsedKb,
		CompileErrorLog: boundTextPtr(verdict.CompileErrorLog, 60000),
		TestCasesReport: make([]control.CaseResultVO, 0, len(verdict.TestCasesReport)),
	}
	for _, cr := range verdict.TestCasesReport {
		resultVO.TestCasesReport = append(resultVO.TestCasesReport, control.CaseResultVO{
			CaseID:         cr.CaseID,
			Status:         cr.Status,
			TimeMs:         cr.TimeMs,
			MemoryKb:       cr.MemoryKb,
			ExpectedOutput: boundTextPtr(cr.ExpectedOutput, 60000),
			ActualOutput:   boundTextPtr(cr.ActualOutput, 60000),
		})
	}

	post(verdict.Status, resultVO, "")
}

func boundTextPtr(s *string, maxRunes int) *string {
	if s == nil {
		return nil
	}
	runes := []rune(*s)
	if len(runes) <= maxRunes {
		return s
	}
	trimmed := string(runes[:maxRunes])
	return &trimmed
}

func (w *Worker) postResult(ctx context.Context, job control.ClaimedJob, status string, result *control.SubmissionResultVO, errorCode string) {
	req := control.ResultRequest{
		LeaseEpoch: job.LeaseEpoch,
		Status:     status,
		Result:     result,
	}
	if errorCode != "" {
		req.Status = ""
		req.Result = nil
		req.ErrorCode = &errorCode
	}

	// Retries only on 429, 5xx, or transient network errors; 401/403/409/413/422 fail immediately
	for attempt := 0; attempt < 5; attempt++ {
		receipt, err := w.client.Result(ctx, job.JobID, req)
		if err == nil {
			log.Printf("[worker] job %s result acknowledged: status=%s", job.JobID, receipt.Status)
			return
		}
		if errors.Is(err, control.ErrConflict) {
			log.Printf("[worker] job %s result rejected with conflict (lease expired)", job.JobID)
			return
		}
		if errors.Is(err, control.ErrUnauthorized) {
			log.Printf("[worker] job %s result rejected with unauthorized: %v", job.JobID, err)
			return
		}
		var protoErr *control.ProtocolError
		if errors.As(err, &protoErr) && (protoErr.StatusCode == 413 || protoErr.StatusCode == 422) {
			log.Printf("[worker] job %s result rejected with non-retryable protocol error: %v", job.JobID, protoErr)
			return
		}
		log.Printf("[worker] job %s post result error (attempt %d): %v", job.JobID, attempt+1, err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Duration(1<<attempt) * 500 * time.Millisecond):
		}
	}
}
