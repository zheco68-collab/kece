#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 后端生产服务同步与更新工具
Deploy backend/target/kece-backend-0.1.0.jar to remote server 10.56.40.238
"""

import os
import sys
import time
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

DEFAULT_HOST = os.environ.get("KECE_BACKEND_HOST", "10.56.40.238")
DEFAULT_PORT = int(os.environ.get("KECE_BACKEND_PORT", "22"))
DEFAULT_USER = os.environ.get("KECE_BACKEND_USER", "zco")
DEFAULT_PASS = os.environ.get("KECE_BACKEND_PASS", "zco070612")
DEFAULT_REMOTE_DIR = "/home/zco/kece"
DEFAULT_SERVICE_NAME = "kece-backend"

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
    ssh.connect(host, port=port, username=user, password=password, timeout=20)

    # 1. 备份现有 jar
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    backup_cmd = f"cp {DEFAULT_REMOTE_DIR}/kece-backend.jar {DEFAULT_REMOTE_DIR}/kece-backend.jar.bak_{timestamp}"
    print(f" 正在备份当前 jar -> kece-backend.jar.bak_{timestamp} ...")
    ssh.exec_command(backup_cmd)

    # 2. 上传新 jar
    remote_temp_jar = f"{DEFAULT_REMOTE_DIR}/kece-backend.jar.new"
    print(f" 正在上传新 Jar 至 {remote_temp_jar} ...")
    sftp = ssh.open_sftp()
    sftp.put(jar_path, remote_temp_jar)
    sftp.close()

    # 3. 原子覆盖并重启服务
    print(" 正在替换文件并重启 systemd 服务...")
    cmd = (
        f"mv {remote_temp_jar} {DEFAULT_REMOTE_DIR}/kece-backend.jar && "
        f"chmod 755 {DEFAULT_REMOTE_DIR}/kece-backend.jar && "
        f"echo '{password}' | sudo -S systemctl restart {DEFAULT_SERVICE_NAME}"
    )
    stdin, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode('utf-8', errors='ignore')
    err = stderr.read().decode('utf-8', errors='ignore')

    # 4. 等待 3 秒并检查服务状态
    print(" 等待服务拉起...")
    time.sleep(3)
    status_cmd = f"systemctl is-active {DEFAULT_SERVICE_NAME}"
    stdin, stdout, stderr = ssh.exec_command(status_cmd)
    status = stdout.read().decode('utf-8', errors='ignore').strip()
    print(f" 后端服务状态: {status}")

    # 5. 查看最近日志
    log_cmd = f"journalctl -u {DEFAULT_SERVICE_NAME} -n 25 --no-pager"
    stdin, stdout, stderr = ssh.exec_command(log_cmd)
    logs = stdout.read().decode('utf-8', errors='ignore')
    print("\n--- 最近启动日志 ---")
    print(logs)

    ssh.close()
    if status == "active":
        print("\n [OK] 后端服务已成功更新并平稳启动！")
        return True
    else:
        print("\n [WARN] 后端状态非 active，请核验日志！")
        return False

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="KECE OJ 后端生产服务同步与更新工具")
    parser.add_argument("--jar", default="backend/target/kece-backend-0.1.0.jar", help="本地 jar 路径")
    parser.add_argument("--host", default=DEFAULT_HOST, help="服务器 IP")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="SSH 端口")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH 用户名")
    parser.add_argument("--password", default=DEFAULT_PASS, help="SSH 密码")
    args = parser.parse_args()

    deploy(args.jar, args.host, args.port, args.user, args.password)
