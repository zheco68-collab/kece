#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE 评测机分池真实端到端容量与突发压测工具 (benchmark-judge.py)

功能特性：
1. 真实调用后端 REST API：登录、创建含真实测试点的编程题与 SQL 题、校验/设置 PERSISTENT 路由。
2. 支持对接已启动的真实 Go judger 专池节点，或通过 --spawn-go-workers 自动注册宿主机组、拉起真实 Go judger 进程、完成自检并启用。
3. 按指定突发批次（如 30,100,300）并发发送真实提交请求，严格区分：
   - API 接收阶段：唯一请求数、接收成功/拒绝/失败数、接收延迟 P50/P95/P99 (ms)、接收吞吐 (req/s)
   - 异步评测阶段：排队等待 P50/P95 (ms)、评测执行耗时 P50/P95 (ms)、首单完成时间 (s)、末单完成时间 (s)、每秒有效完成数 (jobs/s)
4. 每轮突发收敛后自动调用 /admin/judgers/reconciliation 校验 6 大核心不变量（0 违规）。
"""

import argparse
import concurrent.futures
import json
import math
import os
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def percentile(sorted_data, p):
    if not sorted_data:
        return 0.0
    k = (len(sorted_data) - 1) * (p / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return float(sorted_data[int(k)])
    return float(sorted_data[int(f)] * (c - k) + sorted_data[int(c)] * (k - f))


CODE_SAMPLES = {
    "python": ("main.py", "import sys\ndata = sys.stdin.read().split()\nprint(int(data[0]) + int(data[1]))\n"),
    "cpp": (
        "main.cpp",
        "#include <iostream>\nint main(){long long a,b; if(std::cin>>a>>b) std::cout<<(a+b)<<\"\\n\"; return 0;}\n",
    ),
    "java": (
        "Main.java",
        "import java.util.*;\npublic class Main { public static void main(String[] a){ Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); }}\n",
    ),
    "arkts": (
        "main.ets",
        "import { readFileSync } from 'node:fs';\nconst p = readFileSync(0, 'utf8').trim().split(/\\s+/);\nconsole.log(Number(p[0]) + Number(p[1]));\n",
    ),
}


class ApiClient:
    def __init__(self, base_url, timeout=30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def request(self, method, path, body=None, token=None, headers=None):
        url = f"{self.base_url}{path}"
        req_headers = {"Accept": "application/json"}
        if token:
            req_headers["Authorization"] = f"Bearer {token}"
        if headers:
            req_headers.update(headers)
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            req_headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})


class BenchmarkRunner:
    def __init__(self, args):
        self.args = args
        self.api = ApiClient(args.url, timeout=args.http_timeout)
        self.pools = [p.strip() for p in args.pools.split(",") if p.strip()]
        self.admin_token = None
        self.algo_question_id = None
        self.db_question_id = None
        self.spawned_procs = []
        self.temp_dirs = []
        self.routes = {}
        self.nodes = []

    def login(self):
        _, body = self.api.request(
            "POST",
            "/auth/login",
            {"username": self.args.username, "password": self.args.password},
        )
        if body.get("code") != 200:
            raise RuntimeError(f"登录失败: {body}")
        self.admin_token = body["data"]["token"]

    def setup_routes_and_questions(self):
        for pool in self.pools:
            _, route_resp = self.api.request(
                "PUT",
                f"/admin/judge-routes/{pool}/PROBLEM",
                {"dispatchMode": "PERSISTENT"},
                token=self.admin_token,
            )
            if route_resp.get("code") != 200:
                raise RuntimeError(f"设置路由 {pool}/PROBLEM 失败: {route_resp}")
            self.routes[pool] = route_resp["data"]["revision"]

        if any(p in ("cpp", "java", "python", "arkts") for p in self.pools):
            tag = uuid.uuid4().hex[:8]
            _, q_resp = self.api.request(
                "POST",
                "/questions",
                {
                    "title": f"Bench_Algo_{tag}",
                    "description": "Benchmark A+B problem",
                    "difficulty": "EASY",
                    "timeLimit": 2000,
                    "memoryLimit": 256,
                    "languages": ",".join(pool for pool in self.pools if pool != "mysql"),
                    "status": 1,
                },
                token=self.admin_token,
            )
            if q_resp.get("code") != 200:
                raise RuntimeError(f"创建压测编程题失败: {q_resp}")
            self.algo_question_id = q_resp["data"]["id"]

            _, tc_resp = self.api.request(
                "POST",
                f"/questions/{self.algo_question_id}/test-cases",
                {"input": "20 22", "expectedOutput": "42", "isSample": 1},
                token=self.admin_token,
            )
            if tc_resp.get("code") != 200:
                raise RuntimeError(f"添加压测编程题测试点失败: {tc_resp}")

        if "mysql" in self.pools:
            tag = uuid.uuid4().hex[:8]
            _, db_resp = self.api.request(
                "POST",
                "/db-questions",
                {
                    "title": f"Bench_SQL_{tag}",
                    "description": "Benchmark SQL SELECT problem",
                    "type": "SELECT",
                    "schemaSql": "CREATE TABLE t_bench (id INT PRIMARY KEY, val INT);",
                    "referenceSql": "SELECT id, val FROM t_bench WHERE val >= 10 ORDER BY id",
                    "orderSensitive": True,
                    "status": 1,
                    "testCases": [
                        {"seedSql": "INSERT INTO t_bench VALUES (1, 5), (2, 15), (3, 25);", "isSample": 1}
                    ],
                },
                token=self.admin_token,
            )
            if db_resp.get("code") != 200:
                raise RuntimeError(f"创建压测 SQL 题失败: {db_resp}")
            self.db_question_id = db_resp["data"]["id"]

    def spawn_workers_if_requested(self):
        if not self.args.spawn_go_workers:
            return
        if not self.args.judger_bin or not os.path.exists(self.args.judger_bin):
            raise RuntimeError(f"未找到 Go judger 可执行文件: {self.args.judger_bin}")

        total_slots = len(self.pools) * self.args.nodes_per_pool * self.args.slots_per_node
        tag = uuid.uuid4().hex[:6]
        _, hg_resp = self.api.request(
            "POST",
            "/admin/judge-host-groups",
            {"name": f"bench-host-{tag}", "maxSlots": max(total_slots, 4)},
            token=self.admin_token,
        )
        if hg_resp.get("code") != 200:
            raise RuntimeError(f"创建宿主机分组失败: {hg_resp}")
        host_group_id = str(hg_resp["data"]["id"])

        spawned_node_ids = []
        for pool in self.pools:
            for idx in range(self.args.nodes_per_pool):
                node_name = f"bench-{pool}-{idx + 1}-{tag}"
                _, reg_resp = self.api.request(
                    "POST",
                    "/admin/judgers",
                    {
                        "hostGroupId": host_group_id,
                        "name": node_name,
                        "pool": pool,
                        "maxSlots": self.args.slots_per_node,
                    },
                    token=self.admin_token,
                )
                if reg_resp.get("code") != 200:
                    raise RuntimeError(f"注册节点 {node_name} 失败: {reg_resp}")
                node_id = str(reg_resp["data"]["nodeId"])
                enrollment_code = reg_resp["data"]["enrollmentCode"]

                work_dir = tempfile.mkdtemp(prefix=f"kece-bench-{pool}-{idx + 1}-")
                self.temp_dirs.append(work_dir)
                env = os.environ.copy()
                env.update(
                    {
                        "JUDGE_POOL": pool,
                        "JUDGE_CONTROL_URL": self.args.url.rstrip("/"),
                        "JUDGE_ENROLLMENT_CODE": enrollment_code,
                        "JUDGE_CREDENTIAL_FILE": os.path.join(work_dir, "credential.json"),
                        "JUDGE_LOCAL_SLOTS": str(self.args.slots_per_node),
                        "JUDGE_RUNTIME_SPEC": f"{pool}-v1",
                        "JUDGE_RUNTIME_VERSION": "bench-1.0",
                        "WORKSPACE_DIR": os.path.join(work_dir, "ws"),
                        "JUDGE_ARTIFACT_CACHE_DIR": os.path.join(work_dir, "cache"),
                        "JUDGE_MYSQL_HOST": self.args.judge_mysql_host,
                        "JUDGE_MYSQL_PORT": str(self.args.judge_mysql_port),
                        "JUDGE_MYSQL_USER": self.args.judge_mysql_user,
                        "JUDGE_MYSQL_PASSWORD": self.args.judge_mysql_password,
                        "JUDGE_HEARTBEAT_INTERVAL_SECONDS": "2",
                    }
                )
                log_file = open(os.path.join(work_dir, "worker.log"), "w", encoding="utf-8")
                proc = subprocess.Popen(
                    [self.args.judger_bin],
                    env=env,
                    stdout=log_file,
                    stderr=subprocess.STDOUT,
                )
                self.spawned_procs.append((proc, log_file, work_dir, node_name))
                spawned_node_ids.append((node_id, node_name, pool))

        # 等待所有节点完成 join + heartbeat + self-check 并置为 ENABLED
        for node_id, node_name, pool in spawned_node_ids:
            deadline = time.monotonic() + 60.0
            joined = False
            while time.monotonic() < deadline:
                _, detail = self.api.request("GET", f"/admin/judgers/{node_id}", token=self.admin_token)
                node_obj = (detail.get("data") or {}).get("node") or {}
                if node_obj.get("heartbeatAt") and node_obj.get("health") in {"UNVERIFIED", "HEALTHY"}:
                    joined = True
                    break
                time.sleep(0.5)
            if not joined:
                raise RuntimeError(f"节点 {node_name} 在超时期限内未上报心跳")

            # 触发节点自检并等待节点在下一次心跳中领取并完成自检上报 PASSED
            _, sc_resp = self.api.request("POST", f"/admin/judgers/{node_id}/checks", {}, token=self.admin_token)
            if sc_resp.get("code") != 200:
                raise RuntimeError(f"节点 {node_name} 发起自检失败: {sc_resp}")
            check_id = sc_resp["data"]["checkId"]

            check_passed = False
            node_rev = 1
            deadline = time.monotonic() + 75.0
            while time.monotonic() < deadline:
                _, detail = self.api.request("GET", f"/admin/judgers/{node_id}", token=self.admin_token)
                data_obj = detail.get("data") or {}
                node_obj = data_obj.get("node") or {}
                node_rev = node_obj.get("revision", 1)
                check = next((row for row in data_obj.get("checks", []) if row.get("checkId") == check_id), {})
                if check.get("state") == "PASSED" and node_obj.get("health") == "HEALTHY":
                    check_passed = True
                    break
                if check.get("state") in ("FAILED", "EXPIRED"):
                    raise RuntimeError(f"节点 {node_name} 自检失败: {check}")
                time.sleep(1.0)
            if not check_passed:
                raise RuntimeError(f"节点 {node_name} 在超时期限内未完成自检 PASSED")

            _, en_resp = self.api.request(
                "PATCH",
                f"/admin/judgers/{node_id}",
                {"revision": node_rev, "state": "ENABLED"},
                token=self.admin_token,
            )
            if en_resp.get("code") != 200:
                raise RuntimeError(f"启用节点 {node_name} 失败: {en_resp}")
            self.nodes.append({
                "node_id": node_id,
                "name": node_name,
                "pool": pool,
                "slots": self.args.slots_per_node,
                "runtime_version": node_obj.get("runtimeVersion"),
                "check_id": check_id,
            })
            print(f"[*] 节点已就绪并启用: {node_name} (id={node_id}, pool={pool}, slots={self.args.slots_per_node})")
        # 等待 1 个心跳周期使所有 Go worker 感知 ENABLED 状态并进入 Claim 循环
        time.sleep(2.2)

    def run_burst(self, total_submissions, pools):
        print(
            f"\n[+] 开始真实突发压测: 唯一提交数={total_submissions}, 并发客户端={self.args.concurrency}, 目标池={pools}"
        )
        workers = min(total_submissions, self.args.concurrency)
        burst_t0 = 0.0

        def start_burst():
            nonlocal burst_t0
            burst_t0 = time.perf_counter()

        first_wave = threading.Barrier(workers, action=start_burst, timeout=20)

        def submit_one(idx):
            if idx < workers:
                first_wave.wait()
            pool = pools[idx % len(pools)]
            req_t0 = time.perf_counter()
            try:
                if pool == "mysql":
                    _, body = self.api.request(
                        "POST",
                        f"/db-questions/{self.db_question_id}/submit",
                        {"sql": "SELECT id, val FROM t_bench WHERE val >= 10 ORDER BY id"},
                        token=self.admin_token,
                    )
                    kind = "DATABASE"
                else:
                    fname, src = CODE_SAMPLES[pool]
                    _, body = self.api.request(
                        "POST",
                        "/submissions",
                        {
                            "questionId": self.algo_question_id,
                            "language": pool,
                            "codeContent": {fname: src},
                        },
                        token=self.admin_token,
                    )
                    kind = "ALGO"
                latency_ms = (time.perf_counter() - req_t0) * 1000.0
                if body.get("code") == 200 and body.get("data", {}).get("submissionId"):
                    return {
                        "ok": True,
                        "pool": pool,
                        "kind": kind,
                        "submission_id": body["data"]["submissionId"],
                        "request_index": idx,
                        "submit_done_t": time.perf_counter(),
                        "latency_ms": latency_ms,
                    }
                return {"ok": False, "rejected": True, "pool": pool, "request_index": idx,
                        "latency_ms": latency_ms, "error_code": body.get("code")}
            except Exception as exc:
                latency_ms = (time.perf_counter() - req_t0) * 1000.0
                return {"ok": False, "rejected": False, "pool": pool, "request_index": idx,
                        "latency_ms": latency_ms, "error_type": type(exc).__name__}

        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool_exec:
            futures = [pool_exec.submit(submit_one, i) for i in range(total_submissions)]
            submit_results = [f.result() for f in futures]

        ingress_elapsed_s = time.perf_counter() - burst_t0
        accepted = [r for r in submit_results if r.get("ok")]
        rejected = [r for r in submit_results if not r.get("ok") and r.get("rejected")]
        failed = [r for r in submit_results if not r.get("ok") and not r.get("rejected")]
        ingress_latencies = sorted(r["latency_ms"] for r in submit_results)
        ingress_rps = len(accepted) / ingress_elapsed_s if ingress_elapsed_s > 0 else 0.0

        # 轮询等待全部已接收提交收敛到终态
        pending = {(r["kind"], r["submission_id"]): r for r in accepted}
        completed_records = []
        poll_deadline = time.perf_counter() + self.args.converge_timeout

        while pending and time.perf_counter() < poll_deadline:
            finished_keys = []
            for key, item in list(pending.items()):
                kind, sub_id = key
                path = f"/db-submissions/{sub_id}" if kind == "DATABASE" else f"/submissions/{sub_id}"
                try:
                    _, poll_body = self.api.request("GET", path, token=self.admin_token)
                    data = poll_body.get("data") or {}
                    status = data.get("status", "PENDING")
                    if poll_body.get("code") == 200 and status in {
                        "ACCEPTED", "WRONG_ANSWER", "COMPILE_ERROR", "RUNTIME_ERROR",
                        "TIME_LIMIT_EXCEEDED", "MEMORY_LIMIT_EXCEEDED", "SYSTEM_ERROR",
                    }:
                        done_t = time.perf_counter()
                        res_obj = data.get("result") or {}
                        exec_ms = float(res_obj.get("timeUsedMs") or 0.0)
                        total_turnaround_ms = max(0.0, (done_t - item["submit_done_t"]) * 1000.0)
                        completed_records.append(
                            {
                                "kind": kind,
                                "pool": item["pool"],
                                "submission_id": sub_id,
                                "status": status,
                                "done_s_from_burst": done_t - burst_t0,
                                "exec_ms": exec_ms,
                                "turnaround_ms": total_turnaround_ms,
                            }
                        )
                        finished_keys.append(key)
                except Exception:
                    pass
            for k in finished_keys:
                pending.pop(k, None)
            if pending:
                time.sleep(0.15)

        # 执行对账检查
        _, rec_body = self.api.request("GET", "/admin/judgers/reconciliation", token=self.admin_token)
        rec_data = rec_body.get("data") or {}
        violations = rec_data.get("totalViolations", -1)
        healthy = rec_data.get("healthy", False)

        done_times = sorted(r["done_s_from_burst"] for r in completed_records)
        exec_times = sorted(r["exec_ms"] for r in completed_records)
        turnarounds = sorted(r["turnaround_ms"] for r in completed_records)
        ac_count = sum(1 for r in completed_records if r["status"] == "ACCEPTED")

        first_completed_s = done_times[0] if done_times else 0.0
        last_completed_s = done_times[-1] if done_times else (time.perf_counter() - burst_t0)
        effective_cps = len(completed_records) / last_completed_s if last_completed_s > 0 else 0.0

        summary = {
            "pools": pools,
            "unique_requests": total_submissions,
            "concurrency": self.args.concurrency,
            "initial_wave": workers,
            "accepted": len(accepted),
            "rejected": len(rejected),
            "failed": len(failed),
            "ingress_elapsed_s": round(ingress_elapsed_s, 3),
            "ingress_rps": round(ingress_rps, 2),
            "ingress_ms": {
                "p50": round(percentile(ingress_latencies, 50), 2),
                "p95": round(percentile(ingress_latencies, 95), 2),
                "p99": round(percentile(ingress_latencies, 99), 2),
            },
            "completed": len(completed_records),
            "accepted_verdict": ac_count,
            "unconverged": len(pending),
            "exec_ms": {
                "p50": round(percentile(exec_times, 50), 2),
                "p95": round(percentile(exec_times, 95), 2),
            },
            "turnaround_ms": {
                "p50": round(percentile(turnarounds, 50), 2),
                "p95": round(percentile(turnarounds, 95), 2),
            },
            "first_completed_s": round(first_completed_s, 3),
            "last_completed_s": round(last_completed_s, 3),
            "effective_completed_per_s": round(effective_cps, 2),
            "reconcile_healthy": healthy,
            "reconcile_violations": violations,
        }
        print(
            f"[+] 批次完成: 接收={summary['accepted']}/{total_submissions} ({summary['ingress_rps']} req/s, P95={summary['ingress_ms']['p95']}ms) | "
            f"收敛={summary['completed']} (AC={ac_count}, 首单={summary['first_completed_s']}s, 末单={summary['last_completed_s']}s, "
            f"有效完成率={summary['effective_completed_per_s']} jobs/s, 对账违规={violations})"
        )
        for item in submit_results:
            if "submit_done_t" in item:
                item["accepted_s_from_burst"] = round(item.pop("submit_done_t") - burst_t0, 6)
        return summary, {"requests": submit_results, "terminal_observations": completed_records}

    def cleanup(self):
        for proc, log_file, _, _ in self.spawned_procs:
            try:
                proc.terminate()
                proc.wait(timeout=5)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
            try:
                log_file.close()
            except Exception:
                pass
        for d in self.temp_dirs:
            shutil.rmtree(d, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description="KECE 评测机真实端到端容量与突发压测工具")
    parser.add_argument("--url", default="http://127.0.0.1:8080/api/v1", help="后端 API 根地址")
    parser.add_argument("--username", default="admin", help="管理员账号")
    parser.add_argument("--password", default="admin123", help="管理员密码")
    parser.add_argument("--bursts", default="30,100,300", help="逗号分隔的突发批次规模")
    parser.add_argument("--concurrency", type=int, default=16, help="并发客户端线程数")
    parser.add_argument("--pools", default="mysql", help="参与压测的专池列表，逗号分隔")
    parser.add_argument("--workloads", help="用分号分隔批次组合，每组逗号分隔，例如 mysql;python,mysql")
    parser.add_argument("--smoke-pools", action="store_true", help="每个已配置专池先单独执行一次真实提交")
    parser.add_argument("--http-timeout", type=int, default=30, help="单次 HTTP 请求超时秒数")
    parser.add_argument("--converge-timeout", type=int, default=600, help="单批次等待全部终态收敛超时秒数")
    parser.add_argument("--spawn-go-workers", action="store_true", help="自动注册并拉起本地真实 Go judger 节点进程")
    parser.add_argument("--judger-bin", default="", help="Go judger 可执行文件路径")
    parser.add_argument("--nodes-per-pool", type=int, default=2, help="每个池自动拉起的节点数")
    parser.add_argument("--slots-per-node", type=int, default=2, help="每个节点的本地并发槽位数")
    parser.add_argument("--judge-mysql-host", default="127.0.0.1", help="判题专用 MySQL 主机")
    parser.add_argument("--judge-mysql-port", type=int, default=3307, help="判题专用 MySQL 端口")
    parser.add_argument("--judge-mysql-user", default="root", help="判题专用 MySQL 账号")
    parser.add_argument("--judge-mysql-password", default="judge123", help="判题专用 MySQL 密码")
    parser.add_argument("--output-json", default="", help="输出 JSON 汇总文件路径")
    args = parser.parse_args()

    bursts = [int(b.strip()) for b in args.bursts.split(",") if b.strip()]
    runner = BenchmarkRunner(args)
    workloads = [entry.split(",") for entry in args.workloads.split(";")] if args.workloads else [runner.pools]
    if not runner.pools or not bursts or min(bursts) < 1 or args.concurrency < 1 or any(
        not set(pools) <= set(runner.pools) for pools in workloads
    ):
        parser.error("无效的专池、负载、批次数或并发数")
    try:
        runner.login()
        runner.setup_routes_and_questions()
        runner.spawn_workers_if_requested()
        smoke = [(1, [pool]) for pool in runner.pools] if args.smoke_pools else []
        planned = smoke + [(b, pools) for pools in workloads for b in bursts]
        results = [runner.run_burst(b, pools) for b, pools in planned]
    finally:
        runner.cleanup()

    if args.output_json:
        with open(args.output_json, "w", encoding="utf-8") as f:
            json.dump({
                "pools": runner.pools,
                "routes": runner.routes,
                "nodes": runner.nodes,
                "results": [{"summary": summary, **raw} for summary, raw in results],
            }, f, ensure_ascii=False, separators=(",", ":"))

    print("\n================== 真实压测汇总报告 (Markdown) ==================")
    print(
        "| 专池 | 请求数 | 接收成功/失败 | 接收 P50/P95/P99 (ms) | 接收吞吐 (req/s) | 判题回执耗时 P50/P95 (ms) | 首单/末单观测终态 (s) | 收敛速率 (jobs/s) | 终态收敛/AC | 对账违规 |"
    )
    print("|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|")
    for s, _ in results:
        ing = s["ingress_ms"]
        ex = s["exec_ms"]
        print(
            f"| {','.join(s['pools'])} | {s['unique_requests']} | {s['accepted']}/{s['rejected'] + s['failed']} | "
            f"{ing['p50']}/{ing['p95']}/{ing['p99']} | {s['ingress_rps']} | "
            f"{ex['p50']}/{ex['p95']} | "
            f"{s['first_completed_s']}/{s['last_completed_s']} | {s['effective_completed_per_s']} | "
            f"{s['completed']}/{s['accepted_verdict']} | {s['reconcile_violations']} |"
        )
    print("=================================================================")
    return 0 if all(
        s["unique_requests"] == s["accepted"] == s["completed"] == s["accepted_verdict"]
        and s["reconcile_healthy"] and s["reconcile_violations"] == 0 for s, _ in results
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
