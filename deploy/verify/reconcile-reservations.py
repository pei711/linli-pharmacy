#!/usr/bin/env python3
"""Reconcile one settled load-test activity across MySQL and Redis."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
import time


REPO = Path(__file__).resolve().parents[2]
COMPOSE = REPO / "deploy/server-test.compose.yml"


def compose(project: str, service: str, *args: str) -> list[str]:
    return ["docker", "compose", "-p", project, "-f", str(COMPOSE), "exec", "-T", service, *args]


def sql_json(project: str, sql: str) -> dict:
    # Pass the database as a mysql positional argument. Without `hmdp`, the
    # server accepts the connection but every unqualified table query fails
    # with ERROR 1046 (No database selected).
    command = compose(project, "mysql", "sh", "-ec",
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                      '-uroot --batch --skip-column-names hmdp -e "$1"', "sh", sql)
    try:
        result = subprocess.run(command, check=True, text=True, capture_output=True)
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or str(error)).strip()
        raise RuntimeError(f"MySQL query failed in database hmdp: {detail}") from error
    return json.loads(result.stdout.strip())


def redis_value(project: str, key: str) -> str:
    result = subprocess.run(compose(project, "redis", "redis-cli", "--raw", "GET", key),
                            check=True, text=True, capture_output=True)
    value = result.stdout.strip()
    if not value or value == "(nil)":
        raise ValueError(f"Redis key is absent: {key}")
    return value


def build_assertions(report: dict, activity: dict, redis_matches_mysql: bool,
                     max_success_orders: int, expected_activity_stock: int,
                     expected_request_count: int) -> dict[str, bool]:
    failure_codes = report.get("failure_code_counts", {})
    if isinstance(failure_codes, str):
        failure_codes = json.loads(failure_codes)
    status_counts = report.get("request_status_counts", {})
    if isinstance(status_counts, str):
        status_counts = json.loads(status_counts)
    status_total = sum(int(count) for count in status_counts.values())
    failed_status_count = int(status_counts.get("FAILED", 0))
    expired_status_count = int(status_counts.get("EXPIRED", 0))
    return {
        "request_count_matches_load_size": status_total == expected_request_count,
        "status_counts_match_total_requests": status_total == int(report.get("total_requests", status_total)),
        "all_requests_are_terminal": all(status in {"SUCCESS", "FAILED", "EXPIRED"}
                                          for status, count in status_counts.items() if int(count) > 0),
        # Under the load-test contract, every accepted request must resolve as
        # SUCCESS or SOLD_OUT/FAILED. EXPIRED is terminal but signals that the
        # application did not settle the request within its reservation TTL.
        "no_expired_requests": expired_status_count == 0,
        "failed_status_matches_failed_count": failed_status_count == int(report.get("failed_requests", 0)),
        "success_orders_within_limit": int(report["success_orders"]) <= max_success_orders,
        "success_requests_match_orders": int(report["success_requests"]) == int(report["success_orders"]),
        "activity_stock_matches_load_test": int(activity["total_qty"]) == expected_activity_stock,
        "activity_stock_is_not_overdrawn": int(activity["remaining_qty"]) >= 0 and
            int(report["success_orders"]) <= int(activity["total_qty"]),
        "one_success_order_per_user": int(report["duplicate_success_users"]) == 0,
        "no_pending_requests": int(report["pending_requests"]) == 0,
        # A 100-item activity under a 1,000-user unique-token load should only
        # reject excess demand as SOLD_OUT. Any other failure code is an app or
        # test setup error that must be investigated, even if inventory balances.
        "failed_requests_are_explained_by_sold_out": all(
            code == "SOLD_OUT" for code, count in failure_codes.items() if int(count) > 0
        ),
        # SQL maps NULL to UNSPECIFIED so a missing application error code is
        # visible in the report instead of disappearing from JSON_OBJECTAGG.
        # Keep this separate from the SOLD_OUT assertion to make the failure
        # report say explicitly whether the 900 rejected requests were
        # classified at all.
        "failed_error_codes_are_present": all(
            isinstance(code, str) and bool(code.strip()) and code != "UNSPECIFIED"
            and int(count) > 0
            for code, count in failure_codes.items()
        ) and (failed_status_count == 0 or bool(failure_codes)),
        "failed_error_code_distribution_is_complete": sum(
            int(count) for count in failure_codes.values()
        ) == failed_status_count,
        "inventory_nonnegative_and_balanced": int(report["bad_inventory_batches"]) == 0,
        "no_expired_batch_allocations": int(report["expired_order_allocations"]) == 0,
        "expired_batches_not_available_to_activity": int(report["expired_activity_batches"]) == 0,
        "fefo_allocations": int(report["non_fefo_orders"]) == 0,
        "activity_batches_match": int(report["activity_batch_mismatch"]) == 0,
        "redis_matches_mysql": redis_matches_mysql,
    }


def reconciliation_query(activity_id: int) -> str:
    return f"""
SELECT JSON_OBJECT(
 'activity', (SELECT JSON_OBJECT('store_id', store_id, 'medicine_id', medicine_id,
     'remaining_qty', remaining_qty, 'total_qty', total_qty, 'status', status)
   FROM seckill_activity WHERE id={activity_id}),
 'success_orders', (SELECT COUNT(*) FROM pharmacy_order WHERE activity_id={activity_id}),
 'total_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id}),
 'success_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id} AND status='SUCCESS'),
 'failed_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id} AND status='FAILED'),
 'request_status_counts', (SELECT COALESCE(JSON_OBJECTAGG(status, request_count), JSON_OBJECT())
      FROM (SELECT status, COUNT(*) AS request_count FROM seckill_request
            WHERE activity_id={activity_id} GROUP BY status) status_counts),
 'failure_code_counts', (SELECT COALESCE(JSON_OBJECTAGG(failure_code, failure_count), JSON_OBJECT())
      FROM (SELECT COALESCE(failure_code, 'UNSPECIFIED') AS failure_code, COUNT(*) AS failure_count
            FROM seckill_request WHERE activity_id={activity_id} AND status='FAILED'
            GROUP BY COALESCE(failure_code, 'UNSPECIFIED')) failure_counts),
 'success_users', (SELECT COUNT(DISTINCT user_id) FROM pharmacy_order WHERE activity_id={activity_id}),
 'duplicate_success_users', (SELECT COUNT(*) FROM (SELECT user_id FROM pharmacy_order
      WHERE activity_id={activity_id} GROUP BY user_id HAVING COUNT(*) > 1) dup),
 'pending_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id} AND status IN ('INITIALIZING','PROCESSING')),
 'bad_inventory_batches', (SELECT COUNT(*) FROM inventory_batch b JOIN seckill_activity a
      ON a.store_id=b.store_id AND a.medicine_id=b.medicine_id WHERE a.id={activity_id}
      AND (b.on_hand_qty < 0 OR b.available_qty < 0 OR b.activity_reserved_qty < 0 OR b.order_reserved_qty < 0
        OR b.quarantined_qty < 0 OR b.on_hand_qty <> b.available_qty+b.activity_reserved_qty+b.order_reserved_qty+b.quarantined_qty)),
 'expired_order_allocations', (SELECT COUNT(*) FROM order_batch_reservation obr
      JOIN pharmacy_order o ON o.order_no=obr.order_no
      JOIN inventory_batch b ON b.id=obr.batch_id WHERE o.activity_id={activity_id}
      AND b.expire_date < DATE(o.created_at)),
 'expired_activity_batches', (SELECT COUNT(*) FROM seckill_activity_batch ab
      JOIN inventory_batch b ON b.id=ab.batch_id
      WHERE ab.activity_id={activity_id} AND b.expire_date < CURRENT_DATE()),
 'non_fefo_orders', (SELECT COUNT(DISTINCT earlier.order_no) FROM order_batch_reservation earlier
      JOIN order_batch_reservation later ON later.order_no=earlier.order_no AND later.id>earlier.id
      JOIN pharmacy_order o ON o.order_no=earlier.order_no JOIN inventory_batch eb ON eb.id=earlier.batch_id
      JOIN inventory_batch lb ON lb.id=later.batch_id WHERE o.activity_id={activity_id}
      AND (eb.expire_date>lb.expire_date OR (eb.expire_date=lb.expire_date AND earlier.batch_id>later.batch_id))),
 'activity_batch_mismatch', (SELECT COUNT(*) FROM seckill_activity a WHERE a.id={activity_id}
      AND a.remaining_qty <> (SELECT COALESCE(SUM(ab.remaining_qty),0) FROM seckill_activity_batch ab WHERE ab.activity_id=a.id))
)
"""


def settlement_query(activity_id: int) -> str:
    """Return only queue state while Kafka drains; defer inventory joins until settled."""
    return f"""
SELECT JSON_OBJECT(
 'total_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id}),
 'request_status_counts', (SELECT COALESCE(JSON_OBJECTAGG(status, request_count), JSON_OBJECT())
      FROM (SELECT status, COUNT(*) AS request_count FROM seckill_request
            WHERE activity_id={activity_id} GROUP BY status) status_counts),
 'failed_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id} AND status='FAILED'),
 'failure_code_counts', (SELECT COALESCE(JSON_OBJECTAGG(failure_code, failure_count), JSON_OBJECT())
      FROM (SELECT COALESCE(failure_code, 'UNSPECIFIED') AS failure_code, COUNT(*) AS failure_count
            FROM seckill_request WHERE activity_id={activity_id} AND status='FAILED'
            GROUP BY COALESCE(failure_code, 'UNSPECIFIED')) failure_counts),
 'pending_requests', (SELECT COUNT(*) FROM seckill_request WHERE activity_id={activity_id}
      AND status IN ('INITIALIZING','PROCESSING'))
)
"""


def write_report(report: dict, output: Path | None) -> str:
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if output:
        output.write_text(rendered, encoding="utf-8")
    return rendered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="isolated Compose project name")
    parser.add_argument("--activity-id", required=True, type=int)
    parser.add_argument("--max-success-orders", type=int, default=100)
    parser.add_argument("--expected-activity-stock", type=int, default=100,
                        help="expected initial total_qty configured for the load-test activity")
    parser.add_argument("--expected-request-count", type=int, default=1000,
                        help="expected number of unique reservation requests sent by the load driver")
    parser.add_argument("--settlement-timeout-seconds", type=float, default=180,
                        help="wait up to this long for INITIALIZING/PROCESSING requests to settle")
    parser.add_argument("--poll-interval-seconds", type=float, default=2,
                        help="interval between settlement checks")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (args.project == "neighbor-pharmacy-test" or
            re.fullmatch(r"npc-[a-zA-Z0-9_-]{1,55}", args.project)):
        parser.error("project must be neighbor-pharmacy-test or an isolated npc-* project")
    if (args.activity_id < 1 or args.max_success_orders < 1 or args.expected_activity_stock < 1 or
            args.expected_request_count < 1 or
            args.settlement_timeout_seconds <= 0 or args.poll_interval_seconds <= 0):
        parser.error("activity, stock, limits, timeout, and polling interval must be positive")

    activity_id = args.activity_id
    sql = reconciliation_query(activity_id)
    try:
        # Poll only the compact request-state aggregates while Kafka drains.
        # Re-running the full inventory/FEFO join graph every few seconds adds
        # avoidable database load precisely when the consumer is catching up.
        deadline = time.monotonic() + args.settlement_timeout_seconds
        while True:
            settlement = sql_json(args.project, settlement_query(activity_id))
            if int(settlement.get("pending_requests", 0)) == 0:
                try:
                    report = sql_json(args.project, sql)
                except (OSError, subprocess.CalledProcessError, json.JSONDecodeError,
                        RuntimeError, ValueError) as exc:
                    # Preserve the settled request/error-code snapshot even if
                    # a later inventory/FEFO query fails. This is especially
                    # important for distinguishing SOLD_OUT from application
                    # errors when reconciliation itself encounters a SQL error.
                    settlement.update({
                        "activity_id": activity_id,
                        "passed": False,
                        "stage": "full_reconciliation",
                        "full_reconciliation_error": str(exc),
                        "diagnostic": "requests settled, but full inventory reconciliation failed",
                    })
                    rendered = write_report(settlement, args.output)
                    print(rendered, end="")
                    return 2
                break
            if time.monotonic() >= deadline:
                settlement.update({
                    "activity_id": activity_id,
                    "passed": False,
                    "settlement_timeout_seconds": args.settlement_timeout_seconds,
                    "diagnostic": "load-test requests did not settle before reconciliation timeout",
                })
                rendered = write_report(settlement, args.output)
                print(rendered, end="")
                return 2
            time.sleep(args.poll_interval_seconds)
        activity = report.get("activity")
        if not activity:
            raise ValueError(f"activity {activity_id} does not exist")
        stock_key = f"pharmacy:seckill:stock:{activity_id}"
        redis_stock = int(redis_value(args.project, stock_key))
        report.update({"activity_id": activity_id, "redis_stock": redis_stock,
                       "redis_mysql_stock_match": redis_stock == int(activity["remaining_qty"])})
        checks = build_assertions(report, activity, report["redis_mysql_stock_match"],
                                  args.max_success_orders, args.expected_activity_stock,
                                  args.expected_request_count)
        report["assertions"] = checks
        report["passed"] = all(checks.values())
        rendered = write_report(report, args.output)
        print(rendered, end="")
        return 0 if report["passed"] else 1
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, RuntimeError, ValueError) as exc:
        # Keep a current-run artifact even when the very first settlement
        # query fails (for example, a MySQL invocation with no selected DB).
        # Without this, the driver only has stderr and cannot distinguish a
        # SQL/setup failure from a load-settlement timeout in its summary.
        failure_report = {
            "activity_id": activity_id,
            "passed": False,
            "stage": "settlement",
            "error": str(exc),
            "diagnostic": "could not read reservation settlement state",
        }
        rendered = write_report(failure_report, args.output)
        print(rendered, end="", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
