// Command judger starts the KECE dedicated pool judging worker.
package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"syscall"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/artifact"
	"github.com/zheco68-collab/KECE/judger/internal/config"
	"github.com/zheco68-collab/KECE/judger/internal/control"
	"github.com/zheco68-collab/KECE/judger/internal/health"
	"github.com/zheco68-collab/KECE/judger/internal/judge"
	"github.com/zheco68-collab/KECE/judger/internal/sandbox"
	"github.com/zheco68-collab/KECE/judger/internal/worker"
)

var bootIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	cfg := config.Load()
	if err := runPool(ctx, cfg); err != nil {
		log.Fatal(err)
	}
}

func runPool(ctx context.Context, cfg config.Config) error {
	switch cfg.JudgePool {
	case "cpp", "java", "python", "mysql", "arkts":
	default:
		return fmt.Errorf("unsupported JUDGE_POOL %q (expected cpp|java|python|mysql|arkts)", cfg.JudgePool)
	}
	if cfg.JudgeLocalSlots <= 0 {
		return fmt.Errorf("invalid JUDGE_LOCAL_SLOTS %d (must be > 0)", cfg.JudgeLocalSlots)
	}
	if cfg.JudgeControlURL == "" {
		return fmt.Errorf("JUDGE_CONTROL_URL must not be empty")
	}
	if cfg.JudgeCredentialFile == "" {
		return fmt.Errorf("JUDGE_CREDENTIAL_FILE must not be empty")
	}
	if !bootIDPattern.MatchString(cfg.JudgeBootID) {
		return fmt.Errorf("invalid JUDGE_BOOT_ID %q (must match [A-Za-z0-9_-]{16,64})", cfg.JudgeBootID)
	}

	log.Printf("[judger] starting pool worker: pool=%s, control=%s, slots=%d",
		cfg.JudgePool, cfg.JudgeControlURL, cfg.JudgeLocalSlots)

	client, err := control.NewClient(cfg.JudgeControlURL, &http.Client{Timeout: 35 * time.Second})
	if err != nil {
		return fmt.Errorf("control client: %w", err)
	}

	cache, err := artifact.NewCache(cfg.JudgeArtifactCacheDir, cfg.JudgeArtifactCacheLimit)
	if err != nil {
		return fmt.Errorf("artifact cache: %w", err)
	}

	var (
		runner      *sandbox.Runner
		codeExec    worker.Executor
		sqlExec     worker.SQLExecutor
		healthCheck func(context.Context) error
	)

	if cfg.JudgePool == "mysql" {
		mysqlExec := judge.NewSQL(judge.SQLConfig{
			Host:        cfg.JudgeMysqlHost,
			Port:        cfg.JudgeMysqlPort,
			User:        cfg.JudgeMysqlUser,
			Password:    cfg.JudgeMysqlPassword,
			Concurrency: cfg.JudgeLocalSlots,
			Timeout:     cfg.SqlTimeout,
		})
		sqlExec = mysqlExec
		healthCheck = mysqlExec.Ping
		defer mysqlExec.Close()
		go runSQLOrphanJanitor(ctx, mysqlExec)
	} else {
		if err := os.MkdirAll(cfg.WorkspaceDir, 0o777); err != nil {
			return fmt.Errorf("create judge workspace: %w", err)
		}
		runner = &sandbox.Runner{
			Workspace:       cfg.WorkspaceDir,
			CgroupParent:    cfg.CgroupParent,
			CompileTimeout:  cfg.CompileTimeout,
			CompileMemoryMb: cfg.CompileMemoryMb,
			ImageCPP:        cfg.Images.CPP,
			ImageJava:       cfg.Images.Java,
			ImagePython:     cfg.Images.Python,
			ImageArkTS:      cfg.Images.ArkTS,
		}
		runner.PrewarmPool(ctx, cfg.JudgePool)
		codeExec = judge.NewCode(runner, "")
		image := map[string]string{
			"cpp": cfg.Images.CPP, "java": cfg.Images.Java,
			"python": cfg.Images.Python, "arkts": cfg.Images.ArkTS,
		}[cfg.JudgePool]
		healthCheck = func(ctx context.Context) error {
			if err := exec.CommandContext(ctx, "docker", "image", "inspect", image).Run(); err != nil {
				return fmt.Errorf("inspect sandbox image %q: %w", image, err)
			}
			return nil
		}
	}

	w := worker.NewWorker(worker.Config{
		Pool:              cfg.JudgePool,
		EnrollmentCode:    cfg.JudgeEnrollmentCode,
		CredentialFile:    cfg.JudgeCredentialFile,
		BootID:            cfg.JudgeBootID,
		RuntimeSpec:       cfg.JudgeRuntimeSpec,
		RuntimeVersion:    cfg.JudgeRuntimeVersion,
		LocalSlots:        cfg.JudgeLocalSlots,
		ClaimWaitMs:       15000,
		HeartbeatInterval: cfg.JudgeHeartbeatInterval,
		RenewInterval:     15 * time.Second,
		SafetyMargin:      5 * time.Second,
		HealthCheck:       healthCheck,
	}, client, cache, codeExec, sqlExec, runner)

	if cfg.JudgeHealthAddr != "" {
		go func() {
			log.Printf("[judger] internal /healthz listening on %s", cfg.JudgeHealthAddr)
			if err := health.Serve(ctx, cfg.JudgeHealthAddr, w.HealthSnapshot); err != nil {
				log.Printf("[judger] health server stopped: %v", err)
			}
		}()
	}

	return w.Run(ctx)
}

func runSQLOrphanJanitor(ctx context.Context, sqlExec *judge.SQLJudge) {
	sweep := func() {
		sweepCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
		defer cancel()
		dbs, users, err := sqlExec.CleanupOrphanDatabases(sweepCtx, 5*time.Minute)
		if err != nil {
			log.Printf("[judger] sql orphan sweep warning: %v", err)
			return
		}
		if dbs > 0 || users > 0 {
			log.Printf("[judger] sql orphan sweep cleaned %d databases and %d users", dbs, users)
		}
	}
	sweep()
	ticker := time.NewTicker(2 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			sweep()
		}
	}
}
