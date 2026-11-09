// Package config 汇总专池判题节点运行所需的全部配置，均来自环境变量，便于容器化或 systemd 部署。
package config

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

// Images 记录各语言对应的 Docker 沙箱镜像。
type Images struct {
	CPP    string
	Java   string
	Python string
	ArkTS  string
}

// Config 为专池判题节点的运行配置。
type Config struct {
	CompileTimeout  time.Duration
	CompileMemoryMb int
	WorkspaceDir    string
	CgroupParent    string
	Images          Images

	// 数据库判题专用：判题 MySQL 连接与超时。
	JudgeMysqlHost     string
	JudgeMysqlPort     string
	JudgeMysqlUser     string
	JudgeMysqlPassword string
	SqlTimeout         time.Duration

	// 专池控制面配置
	JudgePool               string
	JudgeControlURL         string
	JudgeEnrollmentCode     string
	JudgeCredentialFile     string
	JudgeBootID             string
	JudgeLocalSlots         int
	JudgeRuntimeSpec        string
	JudgeRuntimeVersion     string
	JudgeArtifactCacheDir   string
	JudgeArtifactCacheLimit int64
	JudgeHeartbeatInterval  time.Duration
	JudgeHealthAddr         string
	JudgeHostIdentityFile   string
	JudgeNodeIdentityFile   string
}

// Load 从环境变量读取配置，未设置时使用默认值。
func Load() Config {
	defaultWs := filepath.Join(os.TempDir(), "judge")
	if fi, err := os.Stat("/dev/shm"); err == nil && fi.IsDir() {
		defaultWs = "/dev/shm/kece-judge"
	}

	bootID := getenv("JUDGE_BOOT_ID", "")
	if bootID == "" {
		b := make([]byte, 8)
		_, _ = rand.Read(b)
		bootID = fmt.Sprintf("boot-%d-%s", time.Now().Unix(), hex.EncodeToString(b))
	}

	pool := getenv("JUDGE_POOL", "python")
	runtimeSpec := getenv("JUDGE_RUNTIME_SPEC", pool+"-v1")
	runtimeVer := getenv("JUDGE_RUNTIME_VERSION", "default")

	return Config{
		CompileTimeout:  time.Duration(getenvInt("COMPILE_TIMEOUT_SECONDS", 30)) * time.Second,
		CompileMemoryMb: getenvInt("COMPILE_MEMORY_MB", 1024),
		WorkspaceDir:    getenv("WORKSPACE_DIR", defaultWs),
		CgroupParent:    getenv("JUDGE_CGROUP_PARENT", ""),
		Images: Images{
			CPP:    getenv("IMAGE_CPP", "gcc:13"),
			Java:   getenv("IMAGE_JAVA", "eclipse-temurin:17-jdk"),
			Python: getenv("IMAGE_PYTHON", "python:3.12-slim"),
			ArkTS:  getenv("IMAGE_ARKTS", "kece-sandbox-arkts:latest"),
		},
		JudgeMysqlHost:     getenv("JUDGE_MYSQL_HOST", "127.0.0.1"),
		JudgeMysqlPort:     getenv("JUDGE_MYSQL_PORT", "3307"),
		JudgeMysqlUser:     getenv("JUDGE_MYSQL_USER", "root"),
		JudgeMysqlPassword: getenv("JUDGE_MYSQL_PASSWORD", "judge123"),
		SqlTimeout:         time.Duration(getenvInt("SQL_TIMEOUT_MS", 5000)) * time.Millisecond,

		JudgePool:               pool,
		JudgeControlURL:         getenv("JUDGE_CONTROL_URL", "http://127.0.0.1:8080/api/v1"),
		JudgeEnrollmentCode:     getenv("JUDGE_ENROLLMENT_CODE", ""),
		JudgeCredentialFile:     getenv("JUDGE_CREDENTIAL_FILE", filepath.Join(defaultWs, "credential.json")),
		JudgeBootID:             bootID,
		JudgeLocalSlots:         getenvInt("JUDGE_LOCAL_SLOTS", 2),
		JudgeRuntimeSpec:        runtimeSpec,
		JudgeRuntimeVersion:     runtimeVer,
		JudgeArtifactCacheDir:   getenv("JUDGE_ARTIFACT_CACHE_DIR", filepath.Join(defaultWs, "cache")),
		JudgeArtifactCacheLimit: int64(getenvInt("JUDGE_ARTIFACT_CACHE_LIMIT_MB", 1024)) * 1024 * 1024,
		JudgeHeartbeatInterval:  time.Duration(getenvInt("JUDGE_HEARTBEAT_INTERVAL_SECONDS", 10)) * time.Second,
		JudgeHealthAddr:         getenv("JUDGE_HEALTH_ADDR", ""),
		JudgeHostIdentityFile:   getenv("JUDGE_HOST_IDENTITY_FILE", ""),
		JudgeNodeIdentityFile:   getenv("JUDGE_IDENTITY_FILE", filepath.Join(filepath.Dir(getenv("JUDGE_CREDENTIAL_FILE", filepath.Join(defaultWs, "credential.json"))), "identity.json")),
	}
}

func getenv(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return def
}

func getenvInt(key string, def int) int {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}
