#!/usr/bin/env python3
"""
KECE 评测机分池对账 CLI 工具
执行 6 大核心不变量检查：
1. 指针一致性（ALGO & DATABASE current_judge_job_id）
2. 租约与代次排他性（单任务无并发活跃租约）
3. 终态一致性（完成任务与业务提交状态收敛）
4. 结算事件健康状态（无死单堆积）
5. 节点槽位与宿主机容量约束
6. 队列积压分布与最老任务等待时长
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description="KECE 评测机分池对账工具")
    parser.add_argument("--url", default="http://localhost:8080/api/v1", help="后端 API 根地址")
    parser.add_argument("--username", default="admin", help="管理员账号")
    parser.add_argument("--password", default="admin123", help="管理员密码")
    parser.add_argument("--token", default=os.environ.get("ADMIN_TOKEN"), help="显式指定 JWT Token，默认读取 ADMIN_TOKEN")
    parser.add_argument("--pool", default=None, help="按评测专池筛选 (cpp/java/python/mysql/arkts)")
    parser.add_argument("--format", choices=["json", "csv"], default="json", help="输出格式")
    parser.add_argument("--output", default=None, help="保存报告到指定文件路径")
    parser.add_argument("--fail-on-violation", action="store_true", default=True, help="发现异常时返回退出码 1")
    parser.add_argument("--require-drained", action="store_true", help="切流门禁，有在途任务或未完成结算时返回退出码 1")
    args = parser.parse_args()
    if args.require_drained and (args.format != "json" or args.pool):
        parser.error("--require-drained 需要全站 JSON 对账")

    base_url = args.url.rstrip("/")

    # 1. 登录获取 token
    token = args.token
    if not token:
        login_url = f"{base_url}/auth/login"
        login_data = json.dumps({"username": args.username, "password": args.password}).encode("utf-8")
        req = urllib.request.Request(login_url, data=login_data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                if data.get("code") != 200:
                    print(f"[ERROR] 登录失败: {data}", file=sys.stderr)
                    sys.exit(2)
                token = data["data"]["token"]
        except Exception as e:
            print(f"[ERROR] 连接后端登录端点失败 ({login_url}): {e}", file=sys.stderr)
            sys.exit(2)

    # 2. 查询对账结果
    params = {"format": args.format}
    if args.pool:
        params["pool"] = args.pool
    query_str = urllib.parse.urlencode(params)
    reconcile_url = f"{base_url}/admin/judgers/reconciliation?{query_str}"

    headers = {"Authorization": f"Bearer {token}"}
    req = urllib.request.Request(reconcile_url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode("utf-8")
        print(f"[ERROR] 对账请求返回 HTTP {e.code}: {err_body}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"[ERROR] 请求对账端点失败: {e}", file=sys.stderr)
        sys.exit(2)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(raw)
        print(f"[INFO] 报告已写入: {args.output}")

    if args.format == "csv":
        print(raw)
        if "Status: VIOLATIONS_DETECTED" in raw and args.fail_on_violation:
            sys.exit(1)
        sys.exit(0)

    # JSON 格式输出
    payload = json.loads(raw)
    data = payload.get("data", {})
    healthy = data.get("healthy", False)
    total_violations = data.get("totalViolations", 0)
    violations = data.get("violations", [])
    job_backlog = data.get("jobBacklog", [])
    event_backlog = data.get("eventBacklog", [])
    drained = not any(row.get("count", 0) > 0 and row.get("state") in {"QUEUED", "RUNNING"} for row in job_backlog)
    drained = drained and not any(row.get("count", 0) > 0 and row.get("state") != "DONE" for row in event_backlog)

    print("================================================================================")
    print("                    KECE 评测机集群只读对账报告")
    print("================================================================================")
    print(f"检测时间: {data.get('timestamp')}")
    print(f"健康状态: {'[PASS] 全量不变量满足' if healthy else '[FAIL] 发现不变量违背'}")
    print(f"违规条数: {total_violations}")
    print("--------------------------------------------------------------------------------")

    if violations:
        print("\n[!] 异常违规项明细:")
        for v in violations:
            print(f"  - [{v.get('category')}] {v.get('entityId')}: {v.get('detail')}")
    else:
        print("\n[*] 6 大核心不变量校验全部通过 (无孤儿指针、无租约重叠、无终态不一致、无容量超额)")

    print("\n[+] 评测任务排队分布 (Job Backlog):")
    if job_backlog:
        for b in job_backlog:
            print(f"  - 池: {b.get('pool'):<8} 状态: {b.get('state'):<10} 数量: {b.get('count'):<4} 最早等待: {b.get('oldestCreatedAt')}")
    else:
        print("  - 无排队任务")

    print("\n[+] 结算事件排队分布 (Event Backlog):")
    if event_backlog:
        for e in event_backlog:
            print(f"  - 类型: {e.get('eventType'):<24} 状态: {e.get('state'):<10} 数量: {e.get('count')}")
    else:
        print("  - 无排队结算事件")

    print("================================================================================")

    if args.require_drained:
        print(f"切流排空: {'[PASS] 已排空' if drained else '[FAIL] 仍有在途任务或未完成结算'}")
    if (not healthy and args.fail_on_violation) or (args.require_drained and not drained):
        sys.exit(1)


if __name__ == "__main__":
    main()
