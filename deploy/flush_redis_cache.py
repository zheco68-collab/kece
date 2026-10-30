#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ Redis 业务缓存原子清理工具
Atomic Cache Eviction Tool for KECE OJ (Questions & Problem Sets)
-----------------------------------------------------------------
安全说明：
- 仅清理业务数据查询缓存：`question:cache:*`、`pset:*`、`db:question:cache:*`；
- 严禁触碰判题机运行时心跳键 (`judge:heartbeat:*`、`judge:workers`) 与提交任务状态键 (`sub:*`)。
"""

import os
import sys
import argparse

try:
    import paramiko
except ImportError:
    print("[ERROR] 未安装 paramiko 库，请使用 pip install paramiko 安装。")
    sys.exit(1)

if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

DEFAULT_REMOTE_HOST = os.environ.get("KECE_REMOTE_HOST", "10.56.40.238")
DEFAULT_REMOTE_PORT = int(os.environ.get("KECE_REMOTE_PORT", "22"))
DEFAULT_REMOTE_USER = os.environ.get("KECE_REMOTE_USER", "zco")
DEFAULT_REMOTE_PASS = os.environ.get("KECE_REMOTE_PASS", "zco070612")

def flush_cache(host=DEFAULT_REMOTE_HOST, port=DEFAULT_REMOTE_PORT, user=DEFAULT_REMOTE_USER, password=DEFAULT_REMOTE_PASS):
    print("=" * 60)
    print(" KECE OJ Redis 业务缓存清理")
    print("=" * 60)
    print(f" 连接服务器: {user}@{host}:{port}")

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(host, port=port, username=user, password=password, timeout=10)

    lua_script = (
        "local count = 0; "
        "for _, pattern in ipairs({'question:cache:*', 'pset:*', 'db:question:cache:*'}) do "
        "  local keys = redis.call('keys', pattern); "
        "  for _, k in ipairs(keys) do "
        "    redis.call('del', k); "
        "    count = count + 1; "
        "  end "
        "end; "
        "return count;"
    )

    cmd = f'docker exec kece-redis redis-cli eval "{lua_script}" 0'
    stdin, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode('utf-8', errors='ignore').strip()
    err = stderr.read().decode('utf-8', errors='ignore').strip()

    print(f" 执行指令: {cmd}")
    print(f" [OK] 清理完成！已清除失效缓存键数量: {out}")
    if err:
        print(f" [STDERR]: {err}")

    stdin, stdout, stderr = ssh.exec_command("docker exec kece-redis redis-cli dbsize")
    print(f" 当前 Redis 剩余总键数: {stdout.read().decode('utf-8').strip()}")
    ssh.close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="KECE OJ Redis 业务缓存清理工具")
    parser.add_argument("--remote-host", default=DEFAULT_REMOTE_HOST)
    parser.add_argument("--remote-port", type=int, default=DEFAULT_REMOTE_PORT)
    parser.add_argument("--remote-user", default=DEFAULT_REMOTE_USER)
    parser.add_argument("--remote-pass", default=DEFAULT_REMOTE_PASS)
    args = parser.parse_args()

    flush_cache(args.remote_host, args.remote_port, args.remote_user, args.remote_pass)
