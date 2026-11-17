#!/usr/bin/env python3
"""Kill the consumer after its MySQL commit but before manual Kafka ACK, then assert safe redelivery."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["-f", str(ROOT / "deploy/server-test.compose.yml"),
           "-f", str(ROOT / "deploy/verify/offset-interruption.compose.override.yml")]


def run(project: str, *args: str, input_text: str | None = None, check: bool = True) -> str:
    command = ["docker", "compose", "-p", project, *COMPOSE, *args]
    result = subprocess.run(command, input=input_text, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr}")
    return result.stdout.strip()


def sql_json(project: str, sql: str) -> dict:
    output = run(project, "exec", "-T", "mysql", "sh", "-ec",
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                 '-uroot --batch --skip-column-names hmdp -e "$1"', "sh", sql)
    return json.loads(output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="dedicated npc-offset-* isolated Compose project")
    parser.add_argument("--request-id", required=True, help="accepted PROCESSING request with no order yet")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"npc-offset-[a-zA-Z0-9_-]{1,40}", args.project):
        parser.error("project must start with npc-offset-; the script SIGKILLs its backend")
    try:
        request_id = str(uuid.UUID(args.request_id))
    except ValueError:
        parser.error("request-id must be a UUID")
    if args.timeout < 30:
        parser.error("timeout must be at least 30 seconds")

    try:
        # The consumer can commit within seconds of the reserve POST, so the
        # fixture window (PROCESSING, 0 orders) is racy: poll briefly for it,
        # and also accept (SUCCESS, 1 order) — the consumer is then still in
        # its 60s pre-ACK pause, so the crash-before-ACK injection below is
        # still valid. Either state is a usable starting point.
        fixture_deadline = time.monotonic() + 30
        initial = {"status": None, "orders": -1}
        while time.monotonic() < fixture_deadline:
            initial = sql_json(args.project, f"""
SELECT JSON_OBJECT('status',(SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'orders',(SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'))""")
            if (initial.get("status") == "PROCESSING" and int(initial.get("orders", -1)) == 0) or \
               (initial.get("status") == "SUCCESS" and int(initial.get("orders", -1)) == 1):
                break
            time.sleep(0.5)
        if not ((initial.get("status") == "PROCESSING" and int(initial.get("orders", -1)) == 0) or
                (initial.get("status") == "SUCCESS" and int(initial.get("orders", -1)) == 1)):
            raise ValueError("fixture must be an accepted PROCESSING request with no existing order, "
                             "or a just-committed SUCCESS request still inside the pre-ACK pause")

        run(args.project, "up", "-d", "--no-deps", "--force-recreate", "backend")
        payload = json.dumps({"requestId": request_id}, separators=(",", ":"))
        run(args.project, "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
            "--bootstrap-server", "kafka:19092", "--topic", "pharmacy-seckill-requested",
            "--property", "parse.key=true", "--property", "key.separator=:",
            input_text=f"{request_id}:{payload}\n")

        # The test-only pause is entered after createFromRequest committed and before Redis projection/ACK.
        # The order row and the SUCCESS status flip are separate writes, so
        # wait for BOTH: breaking on orders==1 alone used to abort while the
        # status was still PROCESSING. Cap raised 55s -> 120s: a cold backend
        # restart on this 2 GiB box can take ~90s before the consumer commits.
        deadline = time.monotonic() + min(args.timeout, 120)
        committed = initial
        while time.monotonic() < deadline:
            committed = sql_json(args.project, f"""
SELECT JSON_OBJECT('status',(SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'orders',(SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'),
 'distinct_order_numbers',(SELECT COUNT(DISTINCT order_no) FROM pharmacy_order WHERE request_id='{request_id}'))""")
            if int(committed.get("orders", 0)) == 1 and committed.get("status") == "SUCCESS":
                break
            time.sleep(0.5)
        if int(committed.get("orders", 0)) != 1 or committed.get("status") != "SUCCESS":
            raise TimeoutError(f"MySQL commit not observed before timeout: {committed}")

        # The listener remains paused for 60 seconds. Killing the container simulates process loss
        # after the DB commit and before acknowledgment; restart lets Kafka redeliver the record.
        run(args.project, "kill", "-s", "SIGKILL", "backend")
        run(args.project, "up", "-d", "--no-deps", "backend")
        deadline = time.monotonic() + args.timeout
        final = committed
        redis_state = ""
        while time.monotonic() < deadline:
            final = sql_json(args.project, f"""
SELECT JSON_OBJECT('status',(SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'orders',(SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'),
 'distinct_order_numbers',(SELECT COUNT(DISTINCT order_no) FROM pharmacy_order WHERE request_id='{request_id}'))""")
            redis_state = run(args.project, "exec", "-T", "redis", "redis-cli", "--raw",
                              "HGET", f"pharmacy:seckill:reservation:{request_id}", "state")
            if (final.get("status") == "SUCCESS" and int(final.get("orders", 0)) == 1
                    and redis_state == "SUCCESS"):
                break
            time.sleep(1)

        checks = {
            "mysql_committed_before_injected_crash": committed.get("status") == "SUCCESS" and int(committed.get("orders", 0)) == 1,
            "redelivery_kept_request_successful": final.get("status") == "SUCCESS",
            "redelivery_kept_exactly_one_order": int(final.get("orders", 0)) == 1,
            "redelivery_kept_one_order_number": int(final.get("distinct_order_numbers", 0)) == 1,
            "redelivery_completed_redis_projection": redis_state == "SUCCESS",
        }
        report = {"request_id": request_id, "initial_db": initial, "pre_crash_db": committed,
                  "final_db": final, "final_redis_reservation_state": redis_state,
                  "injection": "SIGKILL backend after MySQL SUCCESS/order commit, before Redis projection and manual Kafka ACK",
                  "assertions": checks, "passed": all(checks.values())}
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0 if report["passed"] else 1
    except (OSError, RuntimeError, ValueError, TimeoutError, subprocess.SubprocessError,
            json.JSONDecodeError, KeyError) as exc:
        print(f"Kafka offset interruption check failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
