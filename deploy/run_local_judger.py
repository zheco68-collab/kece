#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 本机评测机快速启动与接入工具 (Local Judger Runner)
可在本地 Windows / macOS / Linux 启动一个专池评测节点，自动或手动接入远端控制面。
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_CONTROL_URL = os.environ.get("KECE_CONTROL_URL", "http://127.0.0.1:8080/api/v1")
DEFAULT_JWT_SECRET = os.environ.get(
    "JWT_SECRET", "kece-default-jwt-secret-key-please-change-in-production-min-32-chars"
)


def mint_ephemeral_admin_jwt(secret):
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
                "sub": "1",
                "username": "local-operator",
                "roles": ["ADMIN"],
                "type": "access",
                "iat": now,
                "exp": now + 1800,
            },
            separators=(",", ":"),
        ).encode("utf-8")
    )
    signing_input = f"{header}.{payload}".encode("ascii")
    sig = b64url(hmac.new(key_bytes, signing_input, digest).digest())
    return f"{header}.{payload}.{sig}"


def api_request(base_url, method, path, body=None, token=None):
    url = f"{base_url.rstrip('/')}{path}"
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
        return json.loads(raw) if raw else {}


def find_go_binary():
    cand = shutil.which("go")
    if cand:
        return cand
    common_paths = [
        os.path.expanduser("~/go-sdk/go/bin/go.exe"),
        "C:\\Users\\zco\\go-sdk\\go\\bin\\go.exe",
        "C:\\Program Files\\Go\\bin\\go.exe",
        "C:\\Go\\bin\\go.exe",
    ]
    for p in common_paths:
        if os.path.isfile(p):
            return p
    return "go"


def ensure_judger_binary(repo_root):
    judger_dir = os.path.join(repo_root, "judger")
    exe_name = "judger.exe" if os.name == "nt" else "judger"
    bin_dir = os.path.join(judger_dir, "bin")
    bin_path = os.path.join(bin_dir, exe_name)

    if os.path.isfile(bin_path):
        return bin_path

    go_bin = find_go_binary()
    print(f"[*] 正在编译本机评测机可执行文件 ({go_bin}) -> {bin_path} ...")
    os.makedirs(bin_dir, exist_ok=True)
    subprocess.check_call(
        [go_bin, "build", "-trimpath", "-o", bin_path, "./cmd/judger"],
        cwd=judger_dir,
    )
    print(" [OK] 编译完成！")
    return bin_path


def main():
    parser = argparse.ArgumentParser(description="KECE OJ 本机评测机快速启动工具")
    parser.add_argument("--pool", default="python", choices=["python", "cpp", "java", "mysql", "arkts"], help="评测专池类型")
    parser.add_argument("--slots", type=int, default=2, help="本地并发评测槽位")
    parser.add_argument("--control-url", default=DEFAULT_CONTROL_URL, help="主后端控制面 API 地址")
    parser.add_argument("--node-name", default="", help="节点名称（留空默认 local-<os>-<pool>-1）")
    parser.add_argument("--host-group", default="local-pc", help="宿主机预算分组名称")
    parser.add_argument("--enrollment-code", default="", help="网页后台生成的一次性接入码（若提供则跳过自动注册）")
    parser.add_argument("--auto-register", action="store_true", default=True, help="自动调用后台 API 完成注册、自检与启用")
    parser.add_argument("--mysql-host", default="127.0.0.1", help="MySQL 专池判题库主机")
    parser.add_argument("--mysql-port", default="3307", help="MySQL 专池判题库端口")
    parser.add_argument("--mysql-user", default="root", help="MySQL 专池判题库用户名")
    parser.add_argument("--mysql-password", default="judge123", help="MySQL 专池判题库密码")
    args = parser.parse_args()

    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    judger_bin = ensure_judger_binary(repo_root)

    os_tag = "win" if os.name == "nt" else ("mac" if sys.platform == "darwin" else "linux")
    node_name = args.node_name or f"local-{os_tag}-{args.pool}-1"

    local_data_dir = os.path.expanduser(f"~/.kece/judger/{node_name}")
    work_dir = os.path.join(local_data_dir, "work")
    cache_dir = os.path.join(local_data_dir, "cache")
    cred_file = os.path.join(local_data_dir, "credential.json")
    os.makedirs(work_dir, exist_ok=True)
    os.makedirs(cache_dir, exist_ok=True)

    print("=" * 60)
    print(" KECE OJ 本机评测节点启动程序")
    print("=" * 60)
    print(f" 评测专池  : {args.pool} (规格: {args.pool}-v1)")
    print(f" 节点名称  : {node_name}")
    print(f" 本地槽位  : {args.slots} 并发")
    print(f" 控制面URL : {args.control_url}")
    print(f" 数据目录  : {local_data_dir}")
    print("=" * 60)

    token = None
    node_id = None
    enrollment_code = args.enrollment_code

    has_cred = os.path.isfile(cred_file) and os.path.getsize(cred_file) > 0

    if has_cred:
        print(" [*] 检测到本地已有长期接入凭据 (credential.json)，将直接恢复会话连接...")
    elif enrollment_code:
        print(f" [*] 使用指定的一次性接入码: {enrollment_code}")
    elif args.auto_register:
        print(" [*] 正在通过后台 API 自动注册节点并申请接入码...")
        token = mint_ephemeral_admin_jwt(DEFAULT_JWT_SECRET)

        # 确保宿主机组存在
        hg_resp = api_request(args.control_url, "GET", "/admin/judge-host-groups", token=token)
        hg = next((h for h in (hg_resp.get("data") or []) if h.get("name") == args.host_group), None)
        if not hg:
            print(f" [*] 创建宿主机预算组: {args.host_group} (maxSlots=8) ...")
            hg_create = api_request(
                args.control_url,
                "POST",
                "/admin/judge-host-groups",
                {"name": args.host_group, "maxSlots": 8},
                token=token,
            )
            hg = hg_create["data"]
        host_group_id = str(hg["id"])

        # 检查是否已有同名节点
        existing = api_request(args.control_url, "GET", "/admin/judgers?limit=100", token=token).get("data") or []
        found_node = next((n for n in existing if n.get("name") == node_name), None)

        if found_node:
            node_id = str(found_node["nodeId"])
            rev = found_node["revision"]
            print(f" [*] 发现已存在节点记录 {node_name} (id={node_id})，重置接入码...")
            reset_resp = api_request(
                args.control_url, "POST", f"/admin/judgers/{node_id}/enrollment", {"revision": rev}, token=token
            )
            enrollment_code = reset_resp["data"]["enrollmentCode"]
        else:
            print(f" [*] 注册新评测节点: {node_name} ...")
            reg_resp = api_request(
                args.control_url,
                "POST",
                "/admin/judgers",
                {
                    "hostGroupId": host_group_id,
                    "name": node_name,
                    "pool": args.pool,
                    "maxSlots": args.slots,
                },
                token=token,
            )
            node_id = str(reg_resp["data"]["nodeId"])
            enrollment_code = reg_resp["data"]["enrollmentCode"]
        print(f" [OK] 接入码获取成功: {enrollment_code}")

    env = os.environ.copy()
    env.update(
        {
            "JUDGE_POOL": args.pool,
            "JUDGE_RUNTIME_SPEC": f"{args.pool}-v1",
            "JUDGE_RUNTIME_VERSION": "local-dev-1.0",
            "JUDGE_CONTROL_URL": args.control_url.rstrip("/"),
            "JUDGE_LOCAL_SLOTS": str(args.slots),
            "WORKSPACE_DIR": work_dir,
            "JUDGE_ARTIFACT_CACHE_DIR": cache_dir,
            "JUDGE_CREDENTIAL_FILE": cred_file,
            "JUDGE_HEARTBEAT_INTERVAL_SECONDS": "3",
            "JUDGE_MYSQL_HOST": args.mysql_host,
            "JUDGE_MYSQL_PORT": str(args.mysql_port),
            "JUDGE_MYSQL_USER": args.mysql_user,
            "JUDGE_MYSQL_PASSWORD": args.mysql_password,
        }
    )
    if enrollment_code:
        env["JUDGE_ENROLLMENT_CODE"] = enrollment_code

    print("\n[*] 正在启动评测机进程，按 Ctrl+C 可停止...\n")
    proc = subprocess.Popen([judger_bin], env=env)

    # 如果有 token 和 node_id，异步在后台轮询心跳并触发自检 + ENABLED
    if token and node_id:
        def background_enable():
            time.sleep(2)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    detail = api_request(args.control_url, "GET", f"/admin/judgers/{node_id}", token=token).get("data") or {}
                    node_obj = detail.get("node") or {}
                    if node_obj.get("heartbeatAt"):
                        print(f"\n[控制面通知] 节点心跳已就绪，正在触发自检...")
                        sc = api_request(args.control_url, "POST", f"/admin/judgers/{node_id}/checks", {}, token=token)
                        check_id = sc["data"]["checkId"]
                        chk_deadline = time.monotonic() + 45
                        while time.monotonic() < chk_deadline:
                            dt = api_request(args.control_url, "GET", f"/admin/judgers/{node_id}", token=token).get("data") or {}
                            n_obj = dt.get("node") or {}
                            chk = next((c for c in (dt.get("checks") or []) if c.get("checkId") == check_id), {})
                            if chk.get("state") == "PASSED":
                                api_request(args.control_url, "PATCH", f"/admin/judgers/{node_id}", {"revision": n_obj["revision"], "state": "ENABLED"}, token=token)
                                print(f"[控制面通知] 节点 {node_name} 自检 PASSED 并已自动置为 ENABLED！开始接收判题任务。\n")
                                return
                            time.sleep(1)
                        break
                except Exception as ex:
                    pass
                time.sleep(1)

        import threading
        t = threading.Thread(target=background_enable, daemon=True)
        t.start()

    try:
        proc.wait()
    except KeyboardInterrupt:
        print("\n[*] 正在停止评测机进程...")
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        print("[*] 评测机已退出。")


if __name__ == "__main__":
    main()
