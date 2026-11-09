#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 专池评测机生产同步工具。节点接入仅通过后台粘贴 API 地址完成。
Deploy judger/bin/judger-linux-amd64 & systemd multi-pool instances to remote host
"""

import argparse
import json
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
DEFAULT_POOLS = "python,cpp,java,mysql,arkts"
DEFAULT_MYSQL_PASS = os.environ.get("JUDGE_MYSQL_PASSWORD", "judge123")

SYSTEMD_UNIT = """[Unit]
Description=KECE Judge Pool Worker Instance %i
Wants=network-online.target
After=network-online.target docker.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/kece/judger
StateDirectory=kece-judger/%i
Environment=JUDGE_CREDENTIAL_FILE=/var/lib/kece-judger/%i/credential.json
Environment=JUDGE_ARTIFACT_CACHE_DIR=/var/lib/kece-judger/%i/cache
EnvironmentFile=/etc/kece/judger/%i.env
ExecStart=/opt/kece/judger/judger
Restart=always
RestartSec=5s
TimeoutStopSec=40s
LimitNOFILE=65536
LimitNPROC=4096

[Install]
WantedBy=multi-user.target
"""


def open_direct_socket(host, port, timeout=20):
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


def ssh_sudo(ssh, password, cmd):
    wrapped = f"echo '{password}' | sudo -S bash -c {repr(cmd)}"
    _, stdout, _ = ssh.exec_command(wrapped)
    stdout.channel.set_combine_stderr(True)
    out = stdout.read().decode("utf-8", errors="replace").strip()
    rc = stdout.channel.recv_exit_status()
    return rc, out


def remote_api(ssh, control_url, method, path, body=None, token=None):
    headers = ["-H 'Accept: application/json'"]
    if token:
        headers.append(f"-H 'Authorization: Bearer {token}'")
    data_arg = ""
    if body is not None:
        headers.append("-H 'Content-Type: application/json'")
        payload = json.dumps(body).replace("'", "'\"'\"'")
        data_arg = f"-d '{payload}'"
    base = control_url.rstrip("/")
    cmd = f"curl -sS -X {method} {' '.join(headers)} {data_arg} '{base}{path}'"
    _, stdout, stderr = ssh.exec_command(cmd)
    raw = stdout.read().decode("utf-8", errors="replace").strip()
    err = stderr.read().decode("utf-8", errors="replace").strip()
    if not raw:
        raise RuntimeError(f"API {method} {path} 无响应: {err}")
    return json.loads(raw)


def ensure_linux_binary(bin_path):
    bin_path = os.path.abspath(bin_path)
    if os.path.isfile(bin_path) and os.path.getsize(bin_path) > 0:
        return bin_path
    print(f"[*] 正在交叉编译当前源码到 {bin_path} ...")
    os.makedirs(os.path.dirname(bin_path), exist_ok=True)
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    judger_dir = os.path.join(repo_root, "judger")
    go_cmd = shutil.which("go")
    if go_cmd:
        env = os.environ.copy()
        env.update({"GOOS": "linux", "GOARCH": "amd64", "CGO_ENABLED": "0"})
        subprocess.check_call(
            [go_cmd, "build", "-trimpath", "-ldflags=-s -w", "-o", bin_path, "./cmd/judger"],
            cwd=judger_dir,
            env=env,
        )
    else:
        subprocess.check_call(
            ["docker", "run", "--rm", "-v", f"{repo_root}:/workspace", "-w", "/workspace/judger",
             "golang:1.22-alpine", "env", "GOOS=linux", "GOARCH=amd64", "CGO_ENABLED=0",
             "go", "build", "-trimpath", "-ldflags=-s -w", "-o", "./bin/judger-linux-amd64", "./cmd/judger"]
        )
    return bin_path


def build_env_content(pool, instance, slots, api_port, mysql_pass, control_url):
    lines = [
        f"JUDGE_POOL={pool}",
        f"JUDGE_RUNTIME_SPEC={pool}-v1",
        "JUDGE_RUNTIME_VERSION=prod-1.0",
        f"JUDGE_CONTROL_URL={control_url.rstrip('/')}",
        f"JUDGE_LOCAL_SLOTS={slots}",
        f"WORKSPACE_DIR=/var/lib/kece-judger/{instance}/work",
        "JUDGE_HOST_IDENTITY_FILE=/var/lib/kece-judger/host.json",
        f"JUDGE_IDENTITY_FILE=/var/lib/kece-judger/{instance}/identity.json",
        f"JUDGE_HEALTH_ADDR=0.0.0.0:{api_port}",
        "JUDGE_HEARTBEAT_INTERVAL_SECONDS=5",
    ]
    if pool == "mysql":
        lines.extend(
            [
                "JUDGE_MYSQL_HOST=127.0.0.1",
                "JUDGE_MYSQL_PORT=3307",
                "JUDGE_MYSQL_USER=root",
                f"JUDGE_MYSQL_PASSWORD={mysql_pass}",
            ]
        )
    return "\n".join(lines) + "\n"


def backfill_manifests(ssh, control_url, token, kind):
    after_id = 0
    total_published = 0
    all_failures = []
    while True:
        resp = remote_api(
            ssh,
            control_url,
            "POST",
            f"/admin/judging/manifests/backfill?type={kind}&afterId={after_id}&limit=20",
            token=token,
        )
        if resp.get("code") != 200:
            raise RuntimeError(f"回填 {kind} 清单失败: {resp}")
        data = resp["data"]
        total_published += data.get("published", 0)
        all_failures.extend(data.get("failures") or [])
        next_after = data.get("nextAfterId", after_id)
        if not data.get("more") or next_after == after_id:
            break
        after_id = next_after
    print(f" [OK] 存量 {kind} 题目制品清单回填完成: 新增发布 {total_published} 题, 异常 {len(all_failures)} 题")
    if all_failures:
        print(f"      (部分题目无用例或已跳过，示例前 3 条: {all_failures[:3]})")


def deploy(args):
    bin_path = ensure_linux_binary(args.bin)
    pools = [p.strip() for p in args.pools.split(",") if p.strip()]
    size_mb = os.path.getsize(bin_path) / (1024 * 1024)
    control_url = args.control_url

    print("=" * 60)
    print(" KECE OJ 专池评测节点生产部署")
    print("=" * 60)
    print(f" 本地二进制: {bin_path} ({size_mb:.2f} MB)")
    print(f" 目标服务器: {args.user}@{args.host}:{args.port}")
    print(f" 控制面地址: {control_url}")
    print(f" 目标专池  : {', '.join(pools)} (每池 {args.slots} 槽)")

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    sock = open_direct_socket(args.host, args.port, timeout=20)
    ssh.connect(
        args.host, port=args.port, username=args.user, password=args.password, sock=sock, timeout=20, banner_timeout=20
    )

    try:
        # 1. 上传二进制与 systemd 模板并停用旧单进程服务
        sftp = ssh.open_sftp()
        sftp.put(bin_path, "/tmp/kece-judger.new")
        with sftp.file("/tmp/kece-judger@.service", "w") as f:
            f.write(SYSTEMD_UNIT)
        sftp.close()

        ts = time.strftime("%Y%m%d_%H%M%S")
        rc, out = ssh_sudo(
            ssh,
            args.password,
            f"install -d -m 0755 /opt/kece/judger && "
            f"install -d -m 0700 /etc/kece/judger && "
            f"if [ -f /opt/kece/judger/judger ]; then cp /opt/kece/judger/judger /opt/kece/judger/judger.bak_{ts}; fi && "
            f"if [ -f /etc/systemd/system/kece-judger.service ]; then cp /etc/systemd/system/kece-judger.service /home/zco/kece/backups/kece-judger.service.bak_{ts} 2>/dev/null || true; fi && "
            f"mv /tmp/kece-judger.new /opt/kece/judger/judger && "
            f"chmod 0755 /opt/kece/judger/judger && "
            f"mv /tmp/kece-judger@.service /etc/systemd/system/kece-judger@.service && "
            f"chmod 0644 /etc/systemd/system/kece-judger@.service && "
            f"(systemctl disable --now kece-judger.service 2>/dev/null || true) && "
            f"systemctl daemon-reload",
        )
        if rc != 0:
            raise RuntimeError(f"安装二进制与 systemd 模板失败: {out}")
        print(" [OK] 已安装 /opt/kece/judger/judger 与 kece-judger@.service，并停用旧版单进程 kece-judger.service")

        # 2. 确保独立判题 MySQL (3307) 运行
        if "mysql" in pools:
            rc, out = ssh_sudo(
                ssh,
                args.password,
                f"docker start kece-judge-mysql >/dev/null 2>&1 || "
                f"docker run -d --name kece-judge-mysql --restart unless-stopped "
                f"-p 127.0.0.1:3307:3306 -e MYSQL_ROOT_PASSWORD='{args.mysql_password}' "
                f"mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci",
            )
            if rc != 0:
                raise RuntimeError(f"启动 kece-judge-mysql 失败: {out}")
            print(" [OK] 独立判题库 kece-judge-mysql (127.0.0.1:3307) 已就绪")

        # 制品清单回填是独立的维护任务，不参与节点接入。
        if args.backfill:
            if args.admin_token:
                token = args.admin_token
            elif args.admin_user and args.admin_password:
                login = remote_api(
                    ssh, control_url, "POST", "/auth/login", {"username": args.admin_user, "password": args.admin_password}
                )
                if login.get("code") != 200:
                    raise RuntimeError(f"管理员登录失败: {login}")
                token = login["data"]["token"]
            else:
                raise RuntimeError("--backfill 需要 --admin-token 或管理员账号密码")
            backfill_manifests(ssh, control_url, token, "ALGO")
            backfill_manifests(ssh, control_url, token, "DATABASE")

        # 只部署 API 节点。后端在管理员粘贴地址后自动发现、分组和启用。
        for offset, pool in enumerate(pools):
            instance = f"{pool}-1"
            api_port = args.api_port_base + offset
            env_text = build_env_content(
                pool, instance, args.slots, api_port, args.mysql_password, control_url
            )
            sftp = ssh.open_sftp()
            with sftp.file(f"/tmp/{instance}.env", "w") as f:
                f.write(env_text)
            sftp.close()

            rc, out = ssh_sudo(
                ssh,
                args.password,
                f"install -d -m 0755 /var/lib/kece-judger/{instance}/work && "
                f"mv /tmp/{instance}.env /etc/kece/judger/{instance}.env && "
                f"chmod 0600 /etc/kece/judger/{instance}.env && "
                f"systemctl enable kece-judger@{instance} && "
                f"systemctl restart kece-judger@{instance}",
            )
            if rc != 0:
                raise RuntimeError(f"启动 kece-judger@{instance} 失败: {out}")
            print(f" [OK] {pool}: http://{args.api_host or args.host}:{api_port}")
        print("请在管理后台「评测机管理 → 接入评测机」逐条粘贴上述 API 地址；接入、自检和启用将自动完成。")
    finally:
        ssh.close()


def main():
    parser = argparse.ArgumentParser(description="KECE OJ 专池评测机生产部署工具")
    parser.add_argument("--bin", default="judger/bin/judger-linux-amd64", help="Linux amd64 二进制路径")
    parser.add_argument("--host", default=DEFAULT_HOST, help="目标评测机 SSH IP")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="SSH 端口")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH 用户名")
    parser.add_argument("--password", default=DEFAULT_PASS, help="SSH 密码")
    parser.add_argument(
        "--control-url",
        default="http://127.0.0.1:8080/api/v1",
        help="控制面 API 地址（跨机部署填 http://<后端IP>:8080/api/v1）",
    )
    parser.add_argument("--api-host", default="", help="管理员后台可访问的节点 API 主机名或 IP，默认 SSH 主机")
    parser.add_argument("--api-port-base", type=int, default=19091, help="首个专池节点 API 端口")
    parser.add_argument("--pools", default=DEFAULT_POOLS, help="逗号分隔的部署池列表")
    parser.add_argument("--slots", type=int, default=2, help="单池并发槽位数")
    parser.add_argument("--backfill", action="store_true", help="可选：独立执行题库制品清单回填")
    parser.add_argument("--admin-token", default="", help="管理员 Bearer Token（可选）")
    parser.add_argument("--admin-user", default="", help="OJ 管理员账号（可选）")
    parser.add_argument("--admin-password", default="", help="OJ 管理员密码（可选）")
    parser.add_argument("--mysql-password", default=DEFAULT_MYSQL_PASS, help="独立判题 MySQL root 密码")
    args = parser.parse_args()
    deploy(args)


if __name__ == "__main__":
    main()
