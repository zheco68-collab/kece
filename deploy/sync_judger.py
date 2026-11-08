#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 专池评测机生产同步、注册、题库清单回填与启用工具
Deploy judger/bin/judger-linux-amd64 & systemd multi-pool instances to remote host
"""

import argparse
import base64
import hashlib
import hmac
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
DEFAULT_JWT_SECRET = os.environ.get(
    "JWT_SECRET", "kece-default-jwt-secret-key-please-change-in-production-min-32-chars"
)

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


def mint_ephemeral_admin_jwt(secret, user_id="1", username="kece"):
    def b64url(raw_bytes):
        return base64.urlsafe_b64encode(raw_bytes).rstrip(b"=").decode("ascii")

    key_bytes = secret.encode("utf-8")
    if len(key_bytes) >= 64:
        alg, digest = "HS512", hashlib.sha512
    elif len(key_bytes) >= 48:
        alg, digest = "HS384", hashlib.sha384
    else:
        alg, digest = "HS256", hashlib.sha256

    now = int(time.time())
    header = b64url(json.dumps({"alg": alg, "typ": "JWT"}, separators=(",", ":")).encode("utf-8"))
    payload = b64url(
        json.dumps(
            {
                "sub": str(user_id),
                "username": username,
                "roles": ["ADMIN"],
                "type": "access",
                "iat": now,
                "exp": now + 900,
            },
            separators=(",", ":"),
        ).encode("utf-8")
    )
    signing_input = f"{header}.{payload}".encode("ascii")
    sig = b64url(hmac.new(key_bytes, signing_input, digest).digest())
    return f"{header}.{payload}.{sig}"


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
    if os.path.isfile(bin_path):
        return bin_path
    print(f"[*] 本地未找到 {bin_path}，正在交叉编译 Linux amd64 judger ...")
    os.makedirs(os.path.dirname(bin_path), exist_ok=True)
    env = os.environ.copy()
    env.update({"GOOS": "linux", "GOARCH": "amd64", "CGO_ENABLED": "0"})
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    judger_dir = os.path.join(repo_root, "judger")
    subprocess.check_call(
        ["go", "build", "-trimpath", "-ldflags=-s -w", "-o", bin_path, "./cmd/judger"],
        cwd=judger_dir,
        env=env,
    )
    return bin_path


def build_env_content(pool, instance, slots, enrollment_code, mysql_pass, control_url):
    lines = [
        f"JUDGE_POOL={pool}",
        f"JUDGE_RUNTIME_SPEC={pool}-v1",
        "JUDGE_RUNTIME_VERSION=prod-1.0",
        f"JUDGE_CONTROL_URL={control_url.rstrip('/')}",
        f"JUDGE_LOCAL_SLOTS={slots}",
        f"WORKSPACE_DIR=/var/lib/kece-judger/{instance}/work",
        "JUDGE_HEARTBEAT_INTERVAL_SECONDS=5",
    ]
    if enrollment_code:
        lines.append(f"JUDGE_ENROLLMENT_CODE={enrollment_code}")
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

        # 3. 获取管理员令牌
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
            token = mint_ephemeral_admin_jwt(args.jwt_secret)

        # 4. 回填存量 ALGO 与 DATABASE 题目的制品清单
        if not args.skip_backfill:
            backfill_manifests(ssh, control_url, token, "ALGO")
            backfill_manifests(ssh, control_url, token, "DATABASE")

        # 5. 对账宿主机组与各池节点
        hosts_resp = remote_api(ssh, control_url, "GET", "/admin/judge-host-groups", token=token)
        if hosts_resp.get("code") != 200:
            raise RuntimeError(f"查询宿主机组失败: {hosts_resp}")
        host_group = next(
            (h for h in (hosts_resp.get("data") or []) if h.get("name") == args.host_group),
            None,
        )
        if not host_group:
            total_budget = max(len(pools) * args.slots, 10)
            hg_create = remote_api(
                ssh,
                control_url,
                "POST",
                "/admin/judge-host-groups",
                {"name": args.host_group, "maxSlots": total_budget},
                token=token,
            )
            if hg_create.get("code") != 200:
                raise RuntimeError(f"创建宿主机组失败: {hg_create}")
            host_group = hg_create["data"]
        host_group_id = str(host_group["id"])
        print(f" [OK] 宿主机预算组: {host_group['name']} (id={host_group_id}, maxSlots={host_group['maxSlots']})")

        existing_nodes = {
            n["name"]: n
            for n in (remote_api(ssh, control_url, "GET", "/admin/judgers?limit=100", token=token).get("data") or [])
        }

        enrolled = []
        for pool in pools:
            instance = f"{pool}-1"
            node_name = f"{args.node_prefix}-{instance}"
            cred_check_rc, _ = ssh_sudo(ssh, args.password, f"test -s /var/lib/kece-judger/{instance}/credential.json")
            has_cred = cred_check_rc == 0

            node_id = None
            enrollment_code = ""
            if node_name in existing_nodes and has_cred:
                node_id = str(existing_nodes[node_name]["nodeId"])
                print(f" [*] 复用已注册节点 {node_name} (id={node_id}) 并滚动重启...")
            elif node_name in existing_nodes and not has_cred:
                node_id = str(existing_nodes[node_name]["nodeId"])
                rev = existing_nodes[node_name]["revision"]
                reset_resp = remote_api(
                    ssh, control_url, "POST", f"/admin/judgers/{node_id}/enrollment", {"revision": rev}, token=token
                )
                if reset_resp.get("code") != 200:
                    raise RuntimeError(f"重置节点 {node_name} 接入码失败: {reset_resp}")
                enrollment_code = reset_resp["data"]["enrollmentCode"]
            else:
                reg_resp = remote_api(
                    ssh,
                    control_url,
                    "POST",
                    "/admin/judgers",
                    {"hostGroupId": host_group_id, "name": node_name, "pool": pool, "maxSlots": args.slots},
                    token=token,
                )
                if reg_resp.get("code") != 200:
                    raise RuntimeError(f"注册节点 {node_name} 失败: {reg_resp}")
                node_id = str(reg_resp["data"]["nodeId"])
                enrollment_code = reg_resp["data"]["enrollmentCode"]

            env_text = build_env_content(
                pool, instance, args.slots, enrollment_code, args.mysql_password, control_url
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
            enrolled.append((pool, instance, node_name, node_id, bool(enrollment_code)))

        # 6. 等待心跳、执行自检并启用节点，切流 PERSISTENT
        for pool, instance, node_name, node_id, strip_code in enrolled:
            deadline = time.monotonic() + 45.0
            node_obj = {}
            while time.monotonic() < deadline:
                detail = (
                    remote_api(ssh, control_url, "GET", f"/admin/judgers/{node_id}", token=token).get("data") or {}
                )
                node_obj = detail.get("node") or {}
                if node_obj.get("heartbeatAt") and node_obj.get("health") in ("UNVERIFIED", "HEALTHY"):
                    break
                time.sleep(1.0)
            else:
                raise RuntimeError(f"节点 {node_name} 未在 45s 内上报心跳")

            if strip_code:
                ssh_sudo(ssh, args.password, f"sed -i '/^JUDGE_ENROLLMENT_CODE=/d' /etc/kece/judger/{instance}.env")

            if node_obj.get("health") != "HEALTHY":
                sc = remote_api(ssh, control_url, "POST", f"/admin/judgers/{node_id}/checks", {}, token=token)
                if sc.get("code") != 200:
                    raise RuntimeError(f"节点 {node_name} 发起自检失败: {sc}")
                check_id = sc["data"]["checkId"]
                deadline = time.monotonic() + 75.0
                while time.monotonic() < deadline:
                    detail = (
                        remote_api(ssh, control_url, "GET", f"/admin/judgers/{node_id}", token=token).get("data")
                        or {}
                    )
                    node_obj = detail.get("node") or {}
                    chk = next((c for c in (detail.get("checks") or []) if c.get("checkId") == check_id), {})
                    if chk.get("state") == "PASSED" and node_obj.get("health") == "HEALTHY":
                        break
                    if chk.get("state") in ("FAILED", "EXPIRED"):
                        print(f" [WARN] 节点 {node_name} 自检未通过: {chk}")
                        break
                    time.sleep(1.5)

            if node_obj.get("health") == "HEALTHY" and node_obj.get("state") != "ENABLED":
                en = remote_api(
                    ssh,
                    control_url,
                    "PATCH",
                    f"/admin/judgers/{node_id}",
                    {"revision": node_obj["revision"], "state": "ENABLED"},
                    token=token,
                )
                if en.get("code") != 200:
                    raise RuntimeError(f"启用节点 {node_name} 失败: {en}")
                print(f" [OK] 节点 {node_name} (pool={pool}, ver={node_obj.get('runtimeVersion')}) 自检 PASSED 并已置为 ENABLED")
            else:
                print(f" [*] 节点 {node_name} 当前状态: state={node_obj.get('state')}, health={node_obj.get('health')}")

            for scene in ("PROBLEM", "PRACTICE", "EXAM"):
                remote_api(
                    ssh,
                    control_url,
                    "PUT",
                    f"/admin/judge-routes/{pool}/{scene}",
                    {"dispatchMode": "PERSISTENT"},
                    token=token,
                )

        # 7. 全局一致性对账校验
        rec = remote_api(ssh, control_url, "GET", "/admin/judgers/reconciliation", token=token)
        rec_data = rec.get("data") or {}
        print(f"\n [OK] 专池评测集群同步完成！对账结果 totalViolations = {rec_data.get('totalViolations')}")
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
    parser.add_argument("--node-prefix", default="prod", help="节点名称前缀（如 prod、worker2）")
    parser.add_argument("--host-group", default="prod-host-main", help="宿主机预算分组名称")
    parser.add_argument("--pools", default=DEFAULT_POOLS, help="逗号分隔的部署池列表")
    parser.add_argument("--slots", type=int, default=2, help="单池并发槽位数")
    parser.add_argument("--skip-backfill", action="store_true", help="跳过题库制品清单回填（扩容新机器时使用）")
    parser.add_argument("--admin-token", default="", help="管理员 Bearer Token（可选）")
    parser.add_argument("--admin-user", default="", help="OJ 管理员账号（可选）")
    parser.add_argument("--admin-password", default="", help="OJ 管理员密码（可选）")
    parser.add_argument("--jwt-secret", default=DEFAULT_JWT_SECRET, help="后端 JWT_SECRET")
    parser.add_argument("--mysql-password", default=DEFAULT_MYSQL_PASS, help="独立判题 MySQL root 密码")
    args = parser.parse_args()
    deploy(args)


if __name__ == "__main__":
    main()
