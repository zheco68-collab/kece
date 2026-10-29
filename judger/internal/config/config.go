// Package config 汇总判题机运行所需的全部配置，均来自环境变量，便于容器化部署。
package config

import (
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

// Config 为判题机的运行配置。
type Config struct {
	RedisAddr         string
	RedisPassword     string
	QueueKey          string
	ProcessingKey     string
	ResultsKey        string
	StatusKeyPrefix   string
	LeaseKeyPrefix    string
	HeartbeatKey      string
	StatusTTL         time.Duration
	LeaseTTL          time.Duration
	HeartbeatInterval time.Duration
	ReapInterval      time.Duration
	TakeTimeout       time.Duration
	CompileTimeout    time.Duration
	CompileMemoryMb   int
	WorkspaceDir      string
	WorkerID          string
	WorkerConcurrency int
	TestcaseDir       string
	Images            Images
	// 数据库判题专用：独立队列键与判题 MySQL 连接。
	SqlQueueKey        string
	SqlProcessingKey   string
	SqlResultsKey      string
	SqlStatusKeyPrefix string
	SqlLeaseKeyPrefix  string
	JudgeMysqlHost     string
	JudgeMysqlPort     string
	JudgeMysqlUser     string
	JudgeMysqlPassword string
	SqlTimeout         time.Duration
}

// Load 从环境变量读取配置，未设置时使用默认值。
func Load() Config {
	redisAddr := getenv("REDIS_ADDR", "")
	if redisAddr == "" {
		redisAddr = getenv("REDIS_HOST", "127.0.0.1") + ":" + getenv("REDIS_PORT", "6379")
	}
	defaultWs := filepath.Join(os.TempDir(), "judge")
	if fi, err := os.Stat("/dev/shm"); err == nil && fi.IsDir() {
		defaultWs = "/dev/shm/kece-judge"
	}
	return Config{
		RedisAddr:         redisAddr,
		RedisPassword:     getenv("REDIS_PASSWORD", ""),
		QueueKey:          getenv("QUEUE_KEY", "judge:queue"),
		ProcessingKey:     getenv("PROCESSING_KEY", "judge:processing"),
		ResultsKey:        getenv("RESULTS_KEY", "judge:results"),
		StatusKeyPrefix:   getenv("STATUS_KEY_PREFIX", "sub:status:"),
		LeaseKeyPrefix:    getenv("LEASE_KEY_PREFIX", "judge:lease:"),
		HeartbeatKey:      getenv("HEARTBEAT_KEY", "judge:heartbeat:"),
		StatusTTL:         time.Duration(getenvInt("STATUS_TTL_SECONDS", 3600)) * time.Second,
		LeaseTTL:          time.Duration(getenvInt("LEASE_TTL_SECONDS", 120)) * time.Second,
		HeartbeatInterval: time.Duration(getenvInt("HEARTBEAT_INTERVAL_SECONDS", 10)) * time.Second,
		ReapInterval:      time.Duration(getenvInt("REAP_INTERVAL_SECONDS", 20)) * time.Second,
		TakeTimeout:       time.Duration(getenvInt("TAKE_TIMEOUT_SECONDS", 2)) * time.Second,
		CompileTimeout:    time.Duration(getenvInt("COMPILE_TIMEOUT_SECONDS", 30)) * time.Second,
		CompileMemoryMb:   getenvInt("COMPILE_MEMORY_MB", 1024),
		WorkspaceDir:      getenv("WORKSPACE_DIR", defaultWs),
		WorkerID:          getenv("WORKER_ID", hostname()),
		WorkerConcurrency: getenvInt("WORKER_CONCURRENCY", 8),
		TestcaseDir:       getenv("TESTCASE_DIR", "testcases"),
		Images: Images{
			CPP:    getenv("IMAGE_CPP", "gcc:13"),
			Java:   getenv("IMAGE_JAVA", "eclipse-temurin:17-jdk"),
			Python: getenv("IMAGE_PYTHON", "python:3.12-slim"),
			ArkTS:  getenv("IMAGE_ARKTS", "kece-sandbox-arkts:latest"),
		},
		SqlQueueKey:        getenv("SQL_QUEUE_KEY", "judge:sql:queue"),
		SqlProcessingKey:   getenv("SQL_PROCESSING_KEY", "judge:sql:processing"),
		SqlResultsKey:      getenv("SQL_RESULTS_KEY", "judge:sql:results"),
		SqlStatusKeyPrefix: getenv("SQL_STATUS_KEY_PREFIX", "dbsub:status:"),
		SqlLeaseKeyPrefix:  getenv("SQL_LEASE_KEY_PREFIX", "judge:sql:lease:"),
		JudgeMysqlHost:     getenv("JUDGE_MYSQL_HOST", "127.0.0.1"),
		JudgeMysqlPort:     getenv("JUDGE_MYSQL_PORT", "3307"),
		JudgeMysqlUser:     getenv("JUDGE_MYSQL_USER", "root"),
		JudgeMysqlPassword: getenv("JUDGE_MYSQL_PASSWORD", "judge123"),
		SqlTimeout:         time.Duration(getenvInt("SQL_TIMEOUT_MS", 5000)) * time.Millisecond,
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

func hostname() string {
	name, err := os.Hostname()
	if err != nil {
		return "judger"
	}
	return name
}
