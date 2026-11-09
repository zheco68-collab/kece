#!/usr/bin/env python3
"""Start one local judge worker and print the sole admin onboarding input: its API URL."""

import argparse
import os
import re
import shutil
import socket
import subprocess
import sys
from urllib.parse import urlparse


DEFAULT_CONTROL_URL = os.environ.get("KECE_CONTROL_URL", "http://127.0.0.1:8080/api/v1")
POOL_PORTS = {"cpp": 19091, "java": 19092, "python": 19093, "mysql": 19094, "arkts": 19095}


def ensure_judger_binary(repo_root):
    judger_dir = os.path.join(repo_root, "judger")
    exe_name = "judger.exe" if os.name == "nt" else "judger"
    bin_path = os.path.join(judger_dir, "bin", exe_name)
    source_files = [
        os.path.join(directory, name)
        for directory, _, names in os.walk(judger_dir)
        for name in names
        if name.endswith(".go") or name in {"go.mod", "go.sum"}
    ]
    if os.path.isfile(bin_path) and all(os.path.getmtime(path) <= os.path.getmtime(bin_path) for path in source_files):
        return bin_path
    go_bin = shutil.which("go") or "go"
    os.makedirs(os.path.dirname(bin_path), exist_ok=True)
    subprocess.check_call([go_bin, "build", "-trimpath", "-o", bin_path, "./cmd/judger"], cwd=judger_dir)
    return bin_path


def main():
    parser = argparse.ArgumentParser(description="KECE 本机专池评测机")
    parser.add_argument("--pool", default="python", choices=POOL_PORTS)
    parser.add_argument("--slots", type=int, default=2)
    parser.add_argument("--control-url", default=DEFAULT_CONTROL_URL)
    parser.add_argument("--node-name", default="")
    parser.add_argument("--api-port", type=int, default=0, help="发现 API 监听端口，默认按专池分配")
    parser.add_argument("--advertise-host", default="", help="显示给管理员复制的可达主机名或 IP")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", default="3307")
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="judge123")
    args = parser.parse_args()

    if args.slots < 1 or args.slots > 256:
        parser.error("--slots 必须在 1 到 256 之间")
    port = args.api_port or POOL_PORTS[args.pool]
    if port < 1 or port > 65535:
        parser.error("--api-port 超出端口范围")
    parsed = urlparse(args.control_url)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
        parser.error("跨机器控制面须使用 HTTPS；本机回环可用 HTTP")

    os_tag = "win" if os.name == "nt" else ("mac" if sys.platform == "darwin" else "linux")
    node_name = args.node_name or f"local-{os_tag}-{args.pool}-1"
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", node_name):
        parser.error("--node-name 只能使用字母、数字、点、下划线和连字符")
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    judger_bin = ensure_judger_binary(repo_root)
    host_dir = os.path.expanduser("~/.kece/judger")
    data_dir = os.path.join(host_dir, node_name)
    os.makedirs(os.path.join(data_dir, "work"), exist_ok=True)
    os.makedirs(os.path.join(data_dir, "cache"), exist_ok=True)

    env = os.environ.copy()
    env.pop("JUDGE_ENROLLMENT_CODE", None)
    env.update({
        "JUDGE_POOL": args.pool,
        "JUDGE_RUNTIME_SPEC": f"{args.pool}-v1",
        "JUDGE_RUNTIME_VERSION": "local-dev-1.0",
        "JUDGE_CONTROL_URL": args.control_url.rstrip("/"),
        "JUDGE_LOCAL_SLOTS": str(args.slots),
        "WORKSPACE_DIR": os.path.join(data_dir, "work"),
        "JUDGE_ARTIFACT_CACHE_DIR": os.path.join(data_dir, "cache"),
        "JUDGE_CREDENTIAL_FILE": os.path.join(data_dir, "credential.json"),
        "JUDGE_HOST_IDENTITY_FILE": os.path.join(host_dir, "host.json"),
        "JUDGE_IDENTITY_FILE": os.path.join(data_dir, "identity.json"),
        "JUDGE_HEALTH_ADDR": f"0.0.0.0:{port}",
        "JUDGE_HEARTBEAT_INTERVAL_SECONDS": "3",
        "JUDGE_MYSQL_HOST": args.mysql_host,
        "JUDGE_MYSQL_PORT": str(args.mysql_port),
        "JUDGE_MYSQL_USER": args.mysql_user,
        "JUDGE_MYSQL_PASSWORD": args.mysql_password,
    })
    host = args.advertise_host or socket.gethostname()
    print(f"评测机 API 地址： http://{host}:{port}")
    print("在管理后台「评测机管理 → 接入评测机」中粘贴此地址。")
    process = subprocess.Popen([judger_bin], env=env)
    try:
        return process.wait()
    except KeyboardInterrupt:
        process.terminate()
        try:
            return process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            return process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
