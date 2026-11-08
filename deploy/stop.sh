#!/bin/bash
# KECE 停止脚本：支持按 JUDGE_NODE_NAME 仅停止单个专池节点，或停止全部本地开发进程。
# judge-mysql / redis 容器与原生 MySQL 保留。

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$ROOT_DIR/.logs"

if [[ -n "${JUDGE_NODE_NAME:-}" ]]; then
    PID_FILE="$LOG_DIR/judger-${JUDGE_NODE_NAME}.pid"
    if [[ -f "$PID_FILE" ]]; then
        kill "$(cat "$PID_FILE")" 2>/dev/null || true
        rm -f "$PID_FILE"
        echo "已停止专池判题节点: ${JUDGE_NODE_NAME}"
    else
        echo "未找到节点 PID 文件: $PID_FILE"
    fi
    exit 0
fi

for pid_file in "$LOG_DIR"/judger-*.pid; do
    [[ -f "$pid_file" ]] || continue
    kill "$(cat "$pid_file")" 2>/dev/null || true
    rm -f "$pid_file"
done
pkill -f 'mvnw spring-boot:run' 2>/dev/null || true
pkill -f 'vite' 2>/dev/null || true

echo "已停止后端/专池判题节点/前端。容器与原生 MySQL 保留。"
