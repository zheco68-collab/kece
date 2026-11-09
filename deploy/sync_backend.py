#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 后端生产服务同步与更新工具
Deploy backend/target/kece-backend-0.1.0.jar to remote server
"""

import argparse
import os
import socket
import subprocess
import sys
import time

try:
    import paramiko
except ImportError:
    print("[ERROR] 未安装 paramiko 库，请使用 pip install paramiko 安装。")
    sys.exit(1)

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

DEFAULT_HOST = os.environ.get("KECE_BACKEND_HOST", "")
DEFAULT_PORT = int(os.environ.get("KECE_BACKEND_PORT", "22"))
DEFAULT_USER = os.environ.get("KECE_BACKEND_USER", "")
DEFAULT_PASS = os.environ.get("KECE_BACKEND_PASS", "")
DEFAULT_REMOTE_DIR = os.environ.get("KECE_BACKEND_REMOTE_DIR", "/home/zco/kece")
DEFAULT_SERVICE_NAME = "kece-backend"


def open_direct_socket(host, port, timeout=20):
    """若本机存在同 /24 网段网卡，优先绑定该源地址绕过全局代理。"""
    prefix = ".".join(host.split(".")[:3]) + "."
    bind_ip = None
    if os.name == "nt":
        try:
            out = subprocess.check_output("ipconfig", shell=True).decode("gbk", "ignore")
            for line in out.splitlines():
                if "IPv4" in line and ":" in line:
                    cand = line.split(":")[-1].strip()
                    if cand.startswith(prefix):
                        bind_ip = cand
                        break
        except Exception:
            pass
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    if bind_ip:
        sock.bind((bind_ip, 0))
    sock.connect((host, port))
    return sock


def deploy(jar_path, host=DEFAULT_HOST, port=DEFAULT_PORT, user=DEFAULT_USER, password=DEFAULT_PASS):
    jar_path = os.path.abspath(jar_path)
    if not os.path.isfile(jar_path):
        print(f"[ERROR] 构建产物 Jar 不存在: {jar_path}")
        sys.exit(1)

    jar_size_mb = os.path.getsize(jar_path) / (1024 * 1024)
    print("=" * 60)
    print(" KECE OJ 后端生产服务发布")
    print("=" * 60)
    print(f" 本地产物路径: {jar_path} ({jar_size_mb:.2f} MB)")
    print(f" 目标服务器  : {user}@{host}:{port}")
    print(f" 远程部署目录: {DEFAULT_REMOTE_DIR}")

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(" 正在连接远程服务器...")
    sock = open_direct_socket(host, port, timeout=20)
    ssh.connect(host, port=port, username=user, password=password, sock=sock, timeout=20, banner_timeout=20)

    # 1. 上传新 jar 到 /tmp
    remote_temp_jar = "/tmp/kece-backend.jar.new"
    print(f" 正在上传新 Jar 至 {remote_temp_jar} ...")
    sftp = ssh.open_sftp()
    sftp.put(jar_path, remote_temp_jar)
    sftp.close()

    # 2. 备份现有 jar、原子覆盖并重启服务
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    print(f" 正在备份当前 jar -> kece-backend.jar.bak_{timestamp} 并替换重启 systemd 服务...")
    cmd = (
        f"echo '{password}' | sudo -S bash -c \""
        f"cp -f {DEFAULT_REMOTE_DIR}/kece-backend.jar {DEFAULT_REMOTE_DIR}/kece-backend.jar.bak_{timestamp} 2>/dev/null || true; "
        f"mv -f {remote_temp_jar} {DEFAULT_REMOTE_DIR}/kece-backend.jar && "
        f"chmod 755 {DEFAULT_REMOTE_DIR}/kece-backend.jar && "
        f"systemctl restart {DEFAULT_SERVICE_NAME}\""
    )
    _, stdout, _ = ssh.exec_command(cmd)
    stdout.channel.set_combine_stderr(True)
    _ = stdout.read()
    stdout.channel.recv_exit_status()

    # 4. 轮询等待服务启动与 8080 端口就绪（Flyway 迁移可能耗时数秒）
    print(" 等待服务与 Flyway 迁移拉起...")
    status = "unknown"
    ready = False
    for _ in range(30):
        time.sleep(2)
        _, st_out, _ = ssh.exec_command(f"systemctl is-active {DEFAULT_SERVICE_NAME}")
        status = st_out.read().decode("utf-8", errors="ignore").strip()
        _, hc_out, _ = ssh.exec_command("curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/system/judgers/summary || true")
        http_code = hc_out.read().decode("utf-8", errors="ignore").strip()
        if status == "active" and http_code == "200":
            ready = True
            break
    print(f" 后端服务状态: {status} (HTTP ready={ready})")

    # 5. 查看最近日志
    log_cmd = f"echo '{password}' | sudo -S journalctl -u {DEFAULT_SERVICE_NAME} -n 30 --no-pager"
    _, stdout, _ = ssh.exec_command(log_cmd)
    logs = stdout.read().decode("utf-8", errors="ignore")
    print("\n--- 最近启动日志 ---")
    print(logs)

    ssh.close()
    if status == "active" and ready:
        print("\n [OK] 后端服务已成功更新并平稳启动！")
        return True
    print("\n [WARN] 后端状态或 HTTP 探针未就绪，请核验日志！")
    return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="KECE OJ 后端生产服务同步与更新工具")
    parser.add_argument("--jar", default="backend/target/kece-backend-0.1.0.jar", help="本地 jar 路径")
    parser.add_argument("--host", default=DEFAULT_HOST, help="服务器 IP")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="SSH 端口")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH 用户名")
    parser.add_argument("--password", default=DEFAULT_PASS, help="SSH 密码")
    args = parser.parse_args()

    if not deploy(args.jar, args.host, args.port, args.user, args.password):
        sys.exit(1)
