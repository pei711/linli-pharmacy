#!/usr/bin/env python3
"""Replay one accepted reservation event ten times and assert idempotency."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time


def compose(project: str, service: str, *args: str) -> list[str]:
    return ["docker", "compose", "-p", project, "-f", "deploy/server-test.compose.yml",
            "exec", "-T", service, *args]


def run(command: list[str], *, input_text: str | None = None) -> str:
    result = subprocess.run(command, input=input_text, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr}")
    return result.stdout.strip()


def sql_json(project: str, sql: str) -> dict:
    command = compose(project, "mysql", "sh", "-ec",
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                      '-uroot --batch --skip-column-names hmdp -e "$1"', "sh", sql)
    return json.loads(run(command))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="isolated server-test Compose project")
    parser.add_argument("--request-id", required=True, help="UUID of an accepted PROCESSING reservation")
    parser.add_argument("--copies", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    if not (args.project == "neighbor-pharmacy-test" or
            re.fullmatch(r"npc-[a-zA-Z0-9_-]{1,55}", args.project)):
        parser.error("project must be the documented neighbor-pharmacy-test stack or start with npc-")
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", args.request_id):
        parser.error("request-id must be a UUID")
    if args.copies != 10:
        parser.error("the acceptance case requires exactly ten deliveries")

    try:
        before = sql_json(args.project, f"""
SELECT JSON_OBJECT(
 'request_status', (SELECT status FROM seckill_request WHERE request_id='{args.request_id}'),
 'order_count', (SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{args.request_id}')
)""")
        if before["request_status"] not in {"PROCESSING", "SUCCESS"}:
            raise ValueError(f"request must already be accepted; status={before['request_status']!r}")
        if int(before["order_count"]) > 1:
            raise ValueError("request already has duplicate orders before replay")

        payload = json.dumps({"requestId": args.request_id}, separators=(",", ":"))
        messages = "".join(f"{args.request_id}:{payload}\n" for _ in range(args.copies))
        producer = compose(args.project, "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
                           "--bootstrap-server", "kafka:19092", "--topic", "pharmacy-seckill-requested",
                           "--property", "parse.key=true", "--property", "key.separator=:")
        run(producer, input_text=messages)

        deadline = time.monotonic() + args.timeout
        report: dict = {}
        while time.monotonic() < deadline:
            report = sql_json(args.project, f"""
SELECT JSON_OBJECT(
 'request_status', (SELECT status FROM seckill_request WHERE request_id='{args.request_id}'),
 'order_count', (SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{args.request_id}'),
 'distinct_order_numbers', (SELECT COUNT(DISTINCT order_no) FROM pharmacy_order WHERE request_id='{args.request_id}')
)""")
            if report["request_status"] == "SUCCESS" and int(report["order_count"]) >= 1:
                break
            time.sleep(1)
        checks = {
            "request_completed": report.get("request_status") == "SUCCESS",
            "exactly_one_order": int(report.get("order_count", 0)) == 1,
            "one_distinct_order_number": int(report.get("distinct_order_numbers", 0)) == 1,
        }
        report.update({"request_id": args.request_id, "deliveries_sent": args.copies,
                       "assertions": checks, "passed": all(checks.values())})
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if report["passed"] else 1
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"Kafka duplicate replay failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
