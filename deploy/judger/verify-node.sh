#!/usr/bin/env bash
# Trigger one node self-check and verify that the same check reaches PASSED.
set -euo pipefail

NODE_ID="${1:-}"
API_BASE="${2:-${JUDGE_CONTROL_URL:-http://127.0.0.1:8080/api/v1}}"

if [[ ! "$NODE_ID" =~ ^[1-9][0-9]*$ || -z "${ADMIN_TOKEN:-}" ]]; then
  echo "Usage: ADMIN_TOKEN=<jwt> $0 <nodeId> [apiBaseUrl]" >&2
  exit 2
fi

python3 - "$NODE_ID" "$API_BASE" <<'PY'
import json
import os
import sys
import time
import urllib.error
import urllib.request

node_id, base = sys.argv[1:]
path = f"{base.rstrip('/')}/admin/judgers/{node_id}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def request(url, method="GET"):
    req = urllib.request.Request(
        url,
        data=b"{}" if method == "POST" else None,
        method=method,
        headers={"Authorization": f"Bearer {os.environ['ADMIN_TOKEN']}", "Content-Type": "application/json"},
    )
    with opener.open(req, timeout=6) as response:
        body = json.load(response)
    if body.get("code") != 200 or not isinstance(body.get("data"), dict):
        raise ValueError(f"API returned {body.get('code')}: {body.get('message')}")
    return body["data"]


def main():
    timeout = int(os.environ.get("JUDGE_VERIFY_TIMEOUT", "310"))
    if not 1 <= timeout <= 360:
        raise ValueError("JUDGE_VERIFY_TIMEOUT must be between 1 and 360 seconds")
    check_id = request(f"{path}/checks", "POST").get("checkId")
    if not check_id:
        raise ValueError("self-check response has no checkId")
    print(f"Waiting for node {node_id}, check {check_id} ...", flush=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            detail = request(path)
        except urllib.error.HTTPError as exc:
            if exc.code < 500:
                raise
        except (urllib.error.URLError, TimeoutError):
            pass
        else:
            node = detail.get("node") or {}
            check = next((row for row in detail.get("checks", []) if row.get("checkId") == check_id), {})
            state = check.get("state")
            if state == "PASSED":
                if node.get("health") != "HEALTHY":
                    raise ValueError(f"check {check_id} passed but node health is {node.get('health')}")
                print(f"PASSED node={node_id} check={check_id} pool={node.get('pool')} slots={node.get('maxSlots')}")
                return
            if state in {"FAILED", "EXPIRED"}:
                raise ValueError(f"check {check_id} {state}: {check.get('detail') or ''}")
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    raise ValueError(f"check {check_id} did not finish within {timeout}s")


try:
    main()
except (OSError, ValueError) as exc:
    print(f"[FAIL] {exc}", file=sys.stderr)
    sys.exit(1)
PY
