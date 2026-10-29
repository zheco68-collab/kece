#!/bin/bash
# KECE 本地开发启动器 (Linux/macOS)
#
# 复用已在运行的 MySQL(3306) 与 judge-mysql/redis 容器，后台拉起后端/判题机/前端三个本地进程。
# 依赖：go / java(17+) / node / docker 均需在 PATH 上。已运行的进程/容器会自动跳过。

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$ROOT_DIR/.logs"
mkdir -p "$LOG_DIR"

# ---- 1. 基础设施：judge-mysql / redis 容器 ----
if ! nc -z 127.0.0.1 3306 2>/dev/null; then
    echo "警告：3306 无监听，业务 MySQL 未启动，后端将无法连库。" >&2
fi

docker start kece-judge-mysql >/dev/null 2>&1 || \
    docker run -d --name kece-judge-mysql --restart unless-stopped \
        -p 3307:3306 -e MYSQL_ROOT_PASSWORD=judge123 mysql:8.0 \
        --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
docker start kece-redis >/dev/null 2>&1 || \
    docker run -d --name kece-redis --restart unless-stopped -p 6379:6379 redis:7-alpine

# ---- 2. 判题机 ----
if pgrep -x judger >/dev/null 2>&1; then
    echo "判题机已在运行，跳过"
else
    (cd "$ROOT_DIR/judger" && go build -o judger ./cmd/judger)
    (cd "$ROOT_DIR/judger" && \
        REDIS_ADDR="${REDIS_ADDR:-127.0.0.1:6379}" \
        WORKER_CONCURRENCY="${WORKER_CONCURRENCY:-8}" \
        JUDGE_MYSQL_HOST="${JUDGE_MYSQL_HOST:-127.0.0.1}" \
        JUDGE_MYSQL_PORT="${JUDGE_MYSQL_PORT:-3307}" \
        JUDGE_MYSQL_USER="${JUDGE_MYSQL_USER:-root}" \
        JUDGE_MYSQL_PASSWORD="${JUDGE_MYSQL_PASSWORD:-judge123}" \
        TESTCASE_DIR="${TESTCASE_DIR:-$ROOT_DIR/backend/testcases}" \
        nohup ./judger > "$LOG_DIR/judger.log" 2>&1 &)
    echo "判题机已启动（日志：$LOG_DIR/judger.log）"
fi

# ---- 3. 后端 ----
if nc -z 127.0.0.1 8080 2>/dev/null; then
    echo "后端已在运行 (8080)，跳过"
else
    (cd "$ROOT_DIR/backend" && nohup ./mvnw spring-boot:run > "$LOG_DIR/backend.log" 2>&1 &)
    echo "后端已启动 (8080)…"
fi

# ---- 4. 前端 ----
if nc -z 127.0.0.1 5173 2>/dev/null; then
    echo "前端已在运行 (5173)，跳过"
else
    (cd "$ROOT_DIR/frontend" && nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 &)
    echo "前端已启动 (5173)…"
fi

echo ""
echo "后端 http://localhost:8080/api/v1   前端 http://localhost:5173"
echo "日志 $LOG_DIR"
echo "停止 ./deploy/stop.sh"
