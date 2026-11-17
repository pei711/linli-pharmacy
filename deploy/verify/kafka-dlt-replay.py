#!/usr/bin/env python3
"""Inject a poison reservation event, assert DLT routing, then replay a repaired event."""

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
COMPOSE = ROOT / "deploy/server-test.compose.yml"
SOURCE = "pharmacy-seckill-requested"
DLT = SOURCE + ".DLT"


def compose(project: str, service: str, *args: str) -> list[str]:
    return ["docker", "compose", "-p", project, "-f", str(COMPOSE), "exec", "-T", service, *args]


def run(command: list[str], input_text: str | None = None) -> str:
    result = subprocess.run(command, input=input_text, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr}")
    return result.stdout.strip()


def sql_json(project: str, sql: str) -> dict:
    command = compose(project, "mysql", "sh", "-ec",
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                      '-uroot --batch --skip-column-names hmdp -e "$1"', "sh", sql)
    return json.loads(run(command))


def offsets(project: str) -> dict[int, int]:
    output = run(compose(project, "kafka", "/opt/kafka/bin/kafka-get-offsets.sh",
                         "--bootstrap-server", "kafka:19092", "--topic", DLT, "--time", "-1"))
    found: dict[int, int] = {}
    for line in output.splitlines():
        fields = line.rsplit(":", 2)
        if len(fields) == 3 and fields[0] == DLT:
            found[int(fields[1])] = int(fields[2])
    if not found:
        raise RuntimeError(f"could not read offsets for {DLT}: {output!r}")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="isolated neighbor-pharmacy-test or npc-* Compose project")
    parser.add_argument("--request-id", required=True, help="existing successful request UUID used for safe repaired replay")
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (args.project == "neighbor-pharmacy-test" or re.fullmatch(r"npc-[a-zA-Z0-9_-]{1,55}", args.project)):
        parser.error("project must be neighbor-pharmacy-test or start with npc-")
    try:
        request_id = str(uuid.UUID(args.request_id))
    except ValueError:
        parser.error("request-id must be a UUID")
    if args.timeout < 15:
        parser.error("timeout must be at least 15 seconds")

    probe_key = str(uuid.uuid4())
    try:
        before_db = sql_json(args.project, f"""
SELECT JSON_OBJECT('status',(SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'orders',(SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'))""")
        if before_db.get("status") != "SUCCESS" or int(before_db.get("orders", 0)) != 1:
            raise ValueError("replay fixture must already be SUCCESS with exactly one order")

        start_offsets = offsets(args.project)
        poison = json.dumps({"requestId": ""}, separators=(",", ":"))
        producer = compose(args.project, "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
                           "--bootstrap-server", "kafka:19092", "--topic", SOURCE,
                           "--property", "parse.key=true", "--property", "key.separator=:")
        run(producer, f"{probe_key}:{poison}\n")

        deadline = time.monotonic() + args.timeout
        end_offsets = start_offsets
        while time.monotonic() < deadline:
            end_offsets = offsets(args.project)
            if any(end_offsets.get(partition, 0) > offset
                   for partition, offset in start_offsets.items()):
                break
            time.sleep(1)
        if not any(end_offsets.get(partition, 0) > offset
                   for partition, offset in start_offsets.items()):
            raise TimeoutError("poison event did not reach DLT before timeout")

        dlt_lines: list[str] = []
        for partition, start in start_offsets.items():
            count = max(0, end_offsets.get(partition, start) - start)
            if count == 0:
                continue
            consumer = compose(args.project, "kafka", "/opt/kafka/bin/kafka-console-consumer.sh",
                               "--bootstrap-server", "kafka:19092", "--topic", DLT,
                               "--partition", str(partition), "--offset", str(start),
                               "--max-messages", str(count), "--timeout-ms", "10000",
                               "--property", "print.key=true", "--property", "key.separator==")
            output = run(consumer)
            dlt_lines.extend(line for line in output.splitlines() if line)
        matching = [line for line in dlt_lines if line.split("=", 1)[0] == probe_key]
        if len(matching) != 1 or json.loads(matching[0].split("=", 1)[1]).get("requestId") != "":
            raise AssertionError(f"expected exactly one matching poison record in DLT; observed {matching!r}")

        # Repair the invalid payload and republish its business request ID. This safely proves
        # the recovery path without changing DLT records or creating a second order.
        repaired = json.dumps({"requestId": request_id}, separators=(",", ":"))
        run(producer, f"{request_id}:{repaired}\n")
        deadline = time.monotonic() + args.timeout
        after_db = before_db
        while time.monotonic() < deadline:
            after_db = sql_json(args.project, f"""
SELECT JSON_OBJECT('status',(SELECT status FROM seckill_request WHERE request_id='{request_id}'),
 'orders',(SELECT COUNT(*) FROM pharmacy_order WHERE request_id='{request_id}'))""")
            if after_db.get("status") == "SUCCESS" and int(after_db.get("orders", 0)) == 1:
                break
            time.sleep(1)

        checks = {
            "poison_record_routed_to_dlt": len(matching) == 1,
            "repaired_request_remains_successful": after_db.get("status") == "SUCCESS",
            "replay_keeps_exactly_one_order": int(after_db.get("orders", 0)) == 1,
        }
        report = {"probe_key": probe_key, "request_id": request_id,
                  "dlt_record_count_scanned": len(dlt_lines), "initial_db": before_db,
                  "final_db": after_db,
                  "replay_note": "repaired payload republished to source topic; original poison DLT record retained",
                  "assertions": checks, "passed": all(checks.values())}
        rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0 if report["passed"] else 1
    except (OSError, RuntimeError, ValueError, TimeoutError, AssertionError,
            subprocess.SubprocessError, json.JSONDecodeError, KeyError) as exc:
        print(f"Kafka DLT/replay check failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
