#!/usr/bin/env python3
"""Simulate a crashed Outbox Relay lease and assert safe recovery in isolation."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO / "deploy/server-test.compose.yml"


def compose(project: str, service: str, *args: str) -> list[str]:
    return ["docker", "compose", "-p", project, "-f", str(COMPOSE_FILE),
            "exec", "-T", service, *args]


def run(command: list[str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr}")
    return result.stdout.strip()


def sql_json(project: str, sql: str) -> dict:
    cmd = compose(project, "mysql", "sh", "-ec",
                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                  '-uroot --batch --skip-column-names hmdp -e "$1"', "sh", sql)
    return json.loads(run(cmd))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True,
                        help="isolated server-test Compose project (neighbor-pharmacy-test or npc-*)")
    parser.add_argument("--request-id", required=True, help="UUID of a successful reservation")
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (args.project == "neighbor-pharmacy-test" or
            re.fullmatch(r"npc-[a-zA-Z0-9_-]{1,55}", args.project)):
        parser.error("project must be the documented neighbor-pharmacy-test stack or start with npc-")
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", args.request_id):
        parser.error("request-id must be a UUID")
    if args.timeout < 1:
        parser.error("timeout must be positive")

    request_id = args.request_id
    try:
        before = sql_json(args.project, f"""
SELECT JSON_OBJECT(
 'request_status', (SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'order_count', (SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'),
 'event_count', (SELECT COUNT(*) FROM outbox_event WHERE aggregate_id='{request_id}'
     AND event_type='pharmacy.seckill.requested.v1'),
 'event_status', (SELECT status FROM outbox_event WHERE aggregate_id='{request_id}'
     AND event_type='pharmacy.seckill.requested.v1' ORDER BY created_at LIMIT 1)
)""")
        if before["request_status"] != "SUCCESS" or int(before["order_count"]) != 1:
            raise ValueError("request must already be SUCCESS with exactly one order")
        if int(before["event_count"]) != 1 or before["event_status"] != "SENT":
            raise ValueError("request must have exactly one SENT seckill Outbox event")

        # A stale CLAIMED lease is the persistent state left when a Relay process dies.
        changed = sql_json(args.project, f"""
UPDATE outbox_event SET status='CLAIMED', locked_by='verify-crashed-relay',
    locked_at=DATE_SUB(NOW(), INTERVAL 120 SECOND), sent_at=NULL
WHERE aggregate_id='{request_id}' AND event_type='pharmacy.seckill.requested.v1' AND status='SENT';
SELECT JSON_OBJECT('changed_rows', ROW_COUNT())""")
        if int(changed.get("changed_rows", 0)) != 1:
            raise ValueError("could not place exactly one event into stale CLAIMED state")

        run(["docker", "compose", "-p", args.project, "-f", str(COMPOSE_FILE), "restart", "backend"])
        deadline = time.monotonic() + args.timeout
        report: dict = {}
        while time.monotonic() < deadline:
            report = sql_json(args.project, f"""
SELECT JSON_OBJECT(
 'request_status', (SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'order_count', (SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'),
 'event_status', (SELECT status FROM outbox_event WHERE aggregate_id='{request_id}'
     AND event_type='pharmacy.seckill.requested.v1' ORDER BY created_at LIMIT 1),
 'locked_by', (SELECT locked_by FROM outbox_event WHERE aggregate_id='{request_id}'
     AND event_type='pharmacy.seckill.requested.v1' ORDER BY created_at LIMIT 1)
)""")
            if report.get("event_status") == "SENT":
                break
            time.sleep(1)

        checks = {
            "stale_claim_recovered_to_sent": report.get("event_status") == "SENT",
            "request_remains_successful": report.get("request_status") == "SUCCESS",
            "exactly_one_order_after_replay": int(report.get("order_count", 0)) == 1,
            "claim_lease_cleared": report.get("locked_by") is None,
        }
        report.update({"request_id": request_id, "initial_state": before,
                       "assertions": checks, "passed": all(checks.values()),
                       "simulation": "stale CLAIMED lease + backend restart; not a kill at the send/mark boundary"})
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0 if report["passed"] else 1
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"Outbox recovery check failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
