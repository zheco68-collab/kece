// Command judger starts the KECE dedicated pool judging worker.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/zheco68-collab/KECE/judger/internal/artifact"
	"github.com/zheco68-collab/KECE/judger/internal/bootstrap"
	"github.com/zheco68-collab/KECE/judger/internal/config"
	"github.com/zheco68-collab/KECE/judger/internal/control"
	"github.com/zheco68-collab/KECE/judger/internal/discovery"
	"github.com/zheco68-collab/KECE/judger/internal/health"
	"github.com/zheco68-collab/KECE/judger/internal/identity"
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
	ctx, stop := context.WithCancel(ctx)
	defer stop()
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
	if cfg.JudgeHostIdentityFile != "" && cfg.JudgeHealthAddr == "" {
		return fmt.Errorf("JUDGE_HEALTH_ADDR is required when JUDGE_HOST_IDENTITY_FILE is set")
	}

	log.Printf("[judger] starting pool worker: pool=%s, control=%s, slots=%d",
		cfg.JudgePool, cfg.JudgeControlURL, cfg.JudgeLocalSlots)

	client, err := control.NewClient(cfg.JudgeControlURL, &http.Client{Timeout: 35 * time.Second})
	if err != nil {
		return fmt.Errorf("control client: %w", err)
	}
	var controlPlaneID atomic.Value
	controlPlaneID.Store("")
	var nodeIdentity *identity.Identity
	if cfg.JudgeHostIdentityFile != "" {
		id, identityErr := identity.LoadOrCreate(cfg.JudgeHostIdentityFile, cfg.JudgeNodeIdentityFile)
		if identityErr != nil {
			return fmt.Errorf("load node identity: %w", identityErr)
		}
		nodeIdentity = &id
		go refreshControlPlaneID(ctx, client, &controlPlaneID)
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

	var bootstrapAttempt func(context.Context) (bool, error)
	var migrationProof func(string, int64) *control.IdentityProof
	if nodeIdentity != nil {
		migrationProof = func(nodeID string, epoch int64) *control.IdentityProof {
			controlID := controlPlaneID.Load().(string)
			if !discovery.ValidControlPlaneID(controlID) || nodeID == "" || epoch < 1 {
				return nil
			}
			message, err := json.Marshal(map[string]any{
				"controlPlaneId": controlID, "nodeId": nodeID, "sessionEpoch": epoch,
				"instanceId": nodeIdentity.InstanceID, "hostId": nodeIdentity.HostID,
				"nodeKeyId": nodeIdentity.NodeKeyID,
			})
			if err != nil {
				return nil
			}
			payload := base64.RawURLEncoding.EncodeToString(message)
			return &control.IdentityProof{
				PublicKey: nodeIdentity.PublicKey, Payload: payload,
				Signature: base64.RawURLEncoding.EncodeToString(nodeIdentity.Sign([]byte("KECE-IDENTITY-MIGRATION-V1\n" + payload))),
			}
		}
		binder := &bootstrap.Binder{
			Identity: *nodeIdentity, ControlURL: client.BaseURL(), HTTPClient: client.HTTPClient(),
			ControlPlaneID: func() string { return controlPlaneID.Load().(string) },
			CredentialFile: cfg.JudgeCredentialFile, BootID: cfg.JudgeBootID,
			Pool: cfg.JudgePool, RuntimeSpec: cfg.JudgeRuntimeSpec,
			RuntimeVersion: cfg.JudgeRuntimeVersion, LocalSlots: cfg.JudgeLocalSlots,
		}
		bootstrapAttempt = binder.Try
	}
	w := worker.NewWorker(worker.Config{
		Pool:              cfg.JudgePool,
		EnrollmentCode:    cfg.JudgeEnrollmentCode,
		AwaitConnection:   cfg.JudgeHostIdentityFile != "",
		Bootstrap:         bootstrapAttempt,
		MigrationProof:    migrationProof,
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
		var discoveryHandler http.Handler
		if cfg.JudgeHostIdentityFile != "" {
			discoveryHandler = discovery.Handler(*nodeIdentity, func() string {
				return controlPlaneID.Load().(string)
			}, cfg.JudgeBootID, cfg.JudgePool, cfg.JudgeRuntimeSpec, cfg.JudgeRuntimeVersion,
				cfg.JudgePool+"-1", cfg.JudgeLocalSlots, func() (string, string) {
					status := w.HealthSnapshot()
					if status.NodeID == "" {
						return "UNBOUND", "CHECKING"
					}
					if status.Healthy {
						return "BOUND", "READY"
					}
					return "BOUND", "UNHEALTHY"
				})
		}
		done, listenErr := health.Start(ctx, cfg.JudgeHealthAddr, w.HealthSnapshot, discoveryHandler)
		if listenErr != nil {
			return fmt.Errorf("node API listener: %w", listenErr)
		}
		log.Printf("[judger] node API listening on %s", cfg.JudgeHealthAddr)
		go func() {
			if serveErr := <-done; serveErr != nil {
				log.Printf("[judger] node API stopped: %v", serveErr)
				stop()
			}
		}()
	}
	if runner != nil {
		runner.PrewarmPool(ctx, cfg.JudgePool)
	}

	return w.Run(ctx)
}

func refreshControlPlaneID(ctx context.Context, client *control.Client, saved *atomic.Value) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		probeCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		request, err := http.NewRequestWithContext(probeCtx, http.MethodGet,
			client.BaseURL()+"/internal/judgers/bootstrap/context", nil)
		if err == nil {
			var response *http.Response
			response, err = client.HTTPClient().Do(request)
			if err == nil {
				var result struct {
					ControlPlaneID string `json:"controlPlaneId"`
				}
				if response.StatusCode == http.StatusOK {
					err = json.NewDecoder(io.LimitReader(response.Body, 1024)).Decode(&result)
				}
				_ = response.Body.Close()
				if err == nil && response.StatusCode == http.StatusOK {
					if current := saved.Load().(string); current == "" &&
						discovery.ValidControlPlaneID(result.ControlPlaneID) {
						saved.Store(result.ControlPlaneID)
					} else if current != "" && current != result.ControlPlaneID {
						log.Printf("[judger] control plane identity changed; keeping the original identity")
					}
				}
			}
		}
		cancel()
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
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
