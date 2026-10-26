// Command judger 为 KECE 算法评测判题机：从 Redis 拉取任务，
// 在 Docker 沙箱中编译运行，并将结果回写 Redis。
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/config"
	"github.com/zheco68-collab/KECE/judger/internal/judge"
	"github.com/zheco68-collab/KECE/judger/internal/queue"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
)

func main() {
	cfg := config.Load()
	if err := os.MkdirAll(cfg.WorkspaceDir, 0o755); err != nil {
		log.Fatalf("create workspace %s: %v", cfg.WorkspaceDir, err)
	}
	q := queue.New(cfg)
	defer q.Close()

	// 数据库判题使用独立队列键，避免与代码提交的 submissionId 撞车、互抢结果。
	sqlCfg := cfg
	sqlCfg.QueueKey = cfg.SqlQueueKey
	sqlCfg.ProcessingKey = cfg.SqlProcessingKey
	sqlCfg.ResultsKey = cfg.SqlResultsKey
	sqlCfg.StatusKeyPrefix = cfg.SqlStatusKeyPrefix
	sqlCfg.LeaseKeyPrefix = cfg.SqlLeaseKeyPrefix
	sqlQ := queue.New(sqlCfg)
	defer sqlQ.Close()

	runner := &sandbox.Runner{
		Workspace:       cfg.WorkspaceDir,
		CompileTimeout:  cfg.CompileTimeout,
		CompileMemoryMb: cfg.CompileMemoryMb,
		ImageCPP:        cfg.Images.CPP,
		ImageJava:       cfg.Images.Java,
		ImagePython:     cfg.Images.Python,
		ImageArkTS:      cfg.Images.ArkTS,
	}
	j := judge.New(cfg, q, runner)
	defer j.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go heartbeatLoop(ctx, q, cfg.HeartbeatInterval)
	go reapLoop(ctx, q, cfg.ReapInterval)
	for i := 0; i < cfg.WorkerConcurrency; i++ {
		go workerLoop(ctx, cfg, j, q, i+1)
	}
	go reapLoop(ctx, sqlQ, cfg.ReapInterval)
	for i := 0; i < cfg.WorkerConcurrency; i++ {
		go sqlWorkerLoop(ctx, cfg, j, sqlQ, i+1)
	}

	// 启动时在后台预热所有沙箱镜像，将镜像载入 Linux 内存页缓存
	go runner.Prewarm(ctx)

	log.Printf("judger started: worker=%s redis=%s concurrency=%d workspace=%s",
		cfg.WorkerID, cfg.RedisAddr, cfg.WorkerConcurrency, cfg.WorkspaceDir)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	log.Println("shutting down")
	cancel()
	_ = q.ClearHeartbeat(context.Background())
	time.Sleep(time.Second)
}

// workerLoop 循环拉取并处理任务，直至上下文取消。
func workerLoop(ctx context.Context, cfg config.Config, j *judge.Judge, q *queue.Queue, workerID int) {
	for {
		taskJSON, ok, err := q.Take(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[worker-%d] take task: %v", workerID, err)
			time.Sleep(time.Second)
			continue
		}
		if !ok {
			continue // 等待超时，无任务
		}

		task, err := judge.ParseTask(taskJSON)
		if err != nil {
			log.Printf("[worker-%d] parse task: %v", workerID, err)
			_ = q.RemoveProcessing(ctx, taskJSON)
			continue
		}

		_ = q.SetLease(ctx, task.SubmissionID)
		stop := renewLease(ctx, q, task.SubmissionID, cfg.LeaseTTL/2)
		j.Process(ctx, task)
		close(stop)
		_ = q.ClearLease(ctx, task.SubmissionID)
		_ = q.RemoveProcessing(ctx, taskJSON)
	}
}

// sqlWorkerLoop 循环拉取并处理数据库判题任务，与 workerLoop 同构，作用于独立队列。
func sqlWorkerLoop(ctx context.Context, cfg config.Config, j *judge.Judge, sqlQ *queue.Queue, workerID int) {
	for {
		taskJSON, ok, err := sqlQ.Take(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[sql-worker-%d] take sql task: %v", workerID, err)
			time.Sleep(time.Second)
			continue
		}
		if !ok {
			continue // 等待超时，无任务
		}

		task, err := judge.ParseTask(taskJSON)
		if err != nil {
			log.Printf("[sql-worker-%d] parse sql task: %v", workerID, err)
			_ = sqlQ.RemoveProcessing(ctx, taskJSON)
			continue
		}

		_ = sqlQ.SetLease(ctx, task.SubmissionID)
		stop := renewLease(ctx, sqlQ, task.SubmissionID, cfg.LeaseTTL/2)
		j.ProcessSQL(ctx, sqlQ, task)
		close(stop)
		_ = sqlQ.ClearLease(ctx, task.SubmissionID)
		_ = sqlQ.RemoveProcessing(ctx, taskJSON)
	}
}

// renewLease 在处理期间周期性续租，避免长任务被重投；返回停止通道。
func renewLease(ctx context.Context, q *queue.Queue, submissionID int64, interval time.Duration) chan struct{} {
	stop := make(chan struct{})
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ctx.Done():
				return
			case <-ticker.C:
				_ = q.SetLease(ctx, submissionID)
			}
		}
	}()
	return stop
}

// heartbeatLoop 周期性上报判题机存活心跳。
func heartbeatLoop(ctx context.Context, q *queue.Queue, interval time.Duration) {
	// 启动时立即上报一次初始心跳，不等待首个周期
	if err := q.Heartbeat(ctx); err != nil {
		log.Printf("initial heartbeat: %v", err)
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := q.Heartbeat(ctx); err != nil {
				log.Printf("heartbeat: %v", err)
			}
		}
	}
}

// reapLoop 周期性扫描处理中列表，重投租约失效（判题机宕机）的任务。
func reapLoop(ctx context.Context, q *queue.Queue, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			reap(ctx, q)
		}
	}
}

func reap(ctx context.Context, q *queue.Queue) {
	tasks, err := q.ListProcessing(ctx)
	if err != nil {
		log.Printf("reap: %v", err)
		return
	}
	for _, taskJSON := range tasks {
		task, err := judge.ParseTask(taskJSON)
		if err != nil {
			_ = q.RemoveProcessing(ctx, taskJSON)
			continue
		}
		alive, err := q.HasLease(ctx, task.SubmissionID)
		if err != nil {
			continue
		}
		if !alive {
			log.Printf("reap: requeue submission %d", task.SubmissionID)
			if err := q.Requeue(ctx, taskJSON); err != nil {
				log.Printf("reap requeue: %v", err)
			}
		}
	}
}
