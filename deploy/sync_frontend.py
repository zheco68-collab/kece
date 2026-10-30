#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 前端生产构建同步与发布工具
Deploy frontend/dist to production web server
"""

import os
import sys
import tarfile
import tempfile
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

DEFAULT_HOST = os.environ.get("KECE_WEB_HOST", "49.235.220.125")
DEFAULT_PORT = int(os.environ.get("KECE_WEB_PORT", "22"))
DEFAULT_USER = os.environ.get("KECE_WEB_USER", "ubuntu")
DEFAULT_PASS = os.environ.get("KECE_WEB_PASS", "zco070612ZCO@")
DEFAULT_TARGET_DIR = "/opt/1panel/www/sites/oj.zheco68.asia/index"

def deploy(dist_dir="frontend/dist", host=DEFAULT_HOST, port=DEFAULT_PORT,
           user=DEFAULT_USER, password=DEFAULT_PASS, target_dir=DEFAULT_TARGET_DIR):
    dist_dir = os.path.abspath(dist_dir)
    if not os.path.isdir(dist_dir):
        print(f"[ERROR] 目标构建目录不存在: {dist_dir}，请先执行 npm run build。")
        sys.exit(1)

    print("=" * 60)
    print(" KECE OJ 前端静态资源发布")
    print("=" * 60)
    print(f" 本地构建目录: {dist_dir}")
    print(f" 目标服务器  : {user}@{host}:{port}")
    print(f" 远程部署路径: {target_dir}")

    tmp_tar = os.path.join(tempfile.gettempdir(), "kece_frontend_dist.tar.gz")
    print(f" 打包压缩中 -> {tmp_tar}")
    file_count = 0
    with tarfile.open(tmp_tar, "w:gz") as tar:
        for root, _, files in os.walk(dist_dir):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, dist_dir)
                tar.add(full, arcname=rel)
                file_count += 1
    tar_size_mb = os.path.getsize(tmp_tar) / (1024 * 1024)
    print(f" 打包完成: 共 {file_count} 个文件，压缩包大小: {tar_size_mb:.2f} MB")

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f" 正在连接远程服务器...")
    ssh.connect(host, port=port, username=user, password=password, timeout=20)

    remote_tar = "/tmp/kece_frontend_dist.tar.gz"
    print(f" 正在上传压缩包至 {remote_tar} ...")
    sftp = ssh.open_sftp()
    sftp.put(tmp_tar, remote_tar)
    sftp.close()

    print(" 正在解压并更新静态文件...")
    cmd = (
        f"mkdir -p {target_dir} && "
        f"tar -xzf {remote_tar} -C {target_dir} && "
        f"chmod -R 755 {target_dir} && "
        f"rm -f {remote_tar}"
    )
    # 使用 sudo 执行
    sudo_cmd = f"echo '{password}' | sudo -S bash -c \"{cmd}\""
    stdin, stdout, stderr = ssh.exec_command(sudo_cmd)
    out = stdout.read().decode('utf-8')
    err = stderr.read().decode('utf-8')

    ssh.close()
    if os.path.exists(tmp_tar):
        os.remove(tmp_tar)

    print(" [OK] 前端静态资源发布成功！")

def main():
    parser = argparse.ArgumentParser(description="KECE OJ 前端生产构建同步与发布工具")
    parser.add_argument("--dist", default="frontend/dist", help="本地 frontend/dist 目录路径")
    parser.add_argument("--host", default=DEFAULT_HOST, help="Web 服务器 IP/主机名")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="SSH 端口")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH 用户名")
    parser.add_argument("--password", default=DEFAULT_PASS, help="SSH 密码")
    parser.add_argument("--target", default=DEFAULT_TARGET_DIR, help="Web 根目录路径")
    args = parser.parse_args()

    deploy(args.dist, args.host, args.port, args.user, args.password, args.target)

if __name__ == "__main__":
    main()
