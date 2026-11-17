#!/usr/bin/env python3
"""Exercise cancel, pickup, and ended-activity inventory release on an isolated stack.

The supplied order numbers must identify RESERVED fixture orders. The activity
ID must identify a separate activity with no live traffic; this test moves its
end time into the past and leaves the audit state in MySQL.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "deploy/server-test.compose.yml"


def compose(project: str, service: str, *args: str) -> list[str]:
    return ["docker", "compose", "-p", project, "-f", str(COMPOSE), "exec", "-T", service, *args]


def query(project: str, sql: str) -> dict:
    command = compose(project, "mysql", "sh", "-ec",
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                      '-uroot --batch --skip-column-names hmdp -e "$1"', "sh", sql)
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    return json.loads(result.stdout.strip())


def post(base_url: str, path: str, token: str) -> dict:
    request = urllib.request.Request(base_url.rstrip("/") + path, data=b"{}", method="POST",
                                     headers={"Authorization": token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"POST {path} returned HTTP {exc.code}: {detail}") from exc
    if payload.get("success") is False or payload.get("success") == 0:
        raise RuntimeError(f"POST {path} returned application failure: {payload}")
    return payload


def order_snapshot(project: str, order_no: str) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9-]{1,40}", order_no):
        raise ValueError("order number contains unsupported characters")
    return query(project, f"""
SELECT JSON_OBJECT(
 'order', (SELECT JSON_OBJECT('status',status,'quantity',quantity,'request_id',request_id,
   'activity_id',activity_id,'store_id',store_id,'medicine_id',medicine_id)
   FROM pharmacy_order WHERE order_no='{order_no}'),
 'batches', (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('batch_id',b.id,'on_hand',b.on_hand_qty,
   'available',b.available_qty,'activity_reserved',b.activity_reserved_qty,
   'order_reserved',b.order_reserved_qty,'quarantined',b.quarantined_qty,
   'allocated',obr.quantity)),JSON_ARRAY())
   FROM order_batch_reservation obr JOIN inventory_batch b ON b.id=obr.batch_id
   WHERE obr.order_no='{order_no}'),
 'sale_out_qty', (SELECT COALESCE(SUM(quantity),0) FROM stock_movement
   WHERE movement_type='SALE_OUT' AND reference_id='ORDER:PICKUP:{order_no}'),
 'release_qty', (SELECT COALESCE(SUM(quantity),0) FROM stock_movement
   WHERE movement_type='ORDER_RELEASE' AND reference_id='ORDER:CANCEL:{order_no}')
)
""")


def assert_order_transition(before: dict, after: dict, action: str, order_quantity: int) -> None:
    before_batches = {int(row["batch_id"]): row for row in before["batches"]}
    after_batches = {int(row["batch_id"]): row for row in after["batches"]}
    if not before_batches or before_batches.keys() != after_batches.keys():
        raise AssertionError("order batch reservation rows changed or disappeared")
    if sum(int(row["allocated"]) for row in before_batches.values()) != order_quantity:
        raise AssertionError("allocated batch quantity does not equal order quantity")
    for batch_id, prior in before_batches.items():
        current = after_batches[batch_id]
        q = int(prior["allocated"])
        if action == "pickup":
            assert int(current["on_hand"]) == int(prior["on_hand"]) - q, "pickup must consume on-hand stock"
            assert int(current["order_reserved"]) == int(prior["order_reserved"]) - q, "pickup must clear order reservation"
        else:
            assert int(current["on_hand"]) == int(prior["on_hand"]), "cancel must preserve on-hand stock"
            assert int(current["order_reserved"]) == int(prior["order_reserved"]) - q, "cancel must clear order reservation"
    movement_key = "sale_out_qty" if action == "pickup" else "release_qty"
    if int(after[movement_key]) != order_quantity:
        raise AssertionError(f"{action} stock movement quantity must equal order quantity")


def assert_activity_release(before: dict, after: dict) -> int:
    before_batches = {int(row["batch_id"]): row for row in before["batches"]}
    after_batches = {int(row["batch_id"]): row for row in after["batches"]}
    if before_batches.keys() != after_batches.keys():
        raise AssertionError("activity batch rows changed during release")
    for batch_id, before_batch in before_batches.items():
        batch = after_batches[batch_id]
        assert int(batch["activity_remaining"]) == 0, "activity batch remainder must be zero"
        expected_reserved = int(before_batch["activity_reserved"]) - int(before_batch["activity_remaining"])
        assert int(batch["activity_reserved"]) == expected_reserved, "activity reserved inventory must decrease by the released quantity"
    released_batches = [batch for batch in before["batches"] if int(batch["activity_remaining"]) > 0]
    released_quantity = sum(int(batch["activity_remaining"]) for batch in released_batches)
    assert int(after["release_movements"]) == len(released_batches), "each nonempty activity batch must have one release movement"
    assert int(after["release_quantity"]) == released_quantity, "activity release movement quantity must match released stock"
    return released_quantity


def verify_order(project: str, base_url: str, order_no: str, action: str, token: str) -> dict:
    before = order_snapshot(project, order_no)
    order = before.get("order")
    if not order or order["status"] != "RESERVED":
        raise ValueError(f"fixture order {order_no} must exist with RESERVED status")
    path = f"/api/v1/orders/{order_no}/cancel" if action == "cancel" else f"/api/v1/admin/orders/{order_no}/pickup"
    post(base_url, path, token)
    expected = "CANCELLED" if action == "cancel" else "COMPLETED"
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        after = order_snapshot(project, order_no)
        if after.get("order", {}).get("status") == expected:
            break
        time.sleep(1)
    if after.get("order", {}).get("status") != expected:
        raise AssertionError(f"{action} did not reach {expected}: {after}")
    order_quantity = int(order["quantity"])
    assert_order_transition(before, after, action, order_quantity)
    return {"action": action, "order_no": order_no, "status": expected, "before": before, "after": after}


def verify_ended_activity(project: str, activity_id: int) -> dict:
    before = query(project, f"""
SELECT JSON_OBJECT('activity', (SELECT JSON_OBJECT('status',status,'remaining',remaining_qty)
  FROM seckill_activity WHERE id={activity_id}),
 'batches', (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('batch_id',b.id,'activity_remaining',ab.remaining_qty,
  'activity_reserved',b.activity_reserved_qty,'available',b.available_qty,'quarantined',b.quarantined_qty,
  'expiry',b.expire_date)),JSON_ARRAY()) FROM seckill_activity_batch ab
  JOIN inventory_batch b ON b.id=ab.batch_id WHERE ab.activity_id={activity_id}))
""")
    if not before.get("activity") or before["activity"]["status"] not in ("PUBLISHED", "PAUSED"):
        raise ValueError("activity fixture must be PUBLISHED or PAUSED")
    subprocess.run(compose(project, "mysql", "sh", "-ec",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot hmdp -e "$1"', "sh",
        f"UPDATE seckill_activity SET end_at=DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id={activity_id}"),
        check=True, text=True, capture_output=True)
    deadline = time.monotonic() + 90
    after = {}
    while time.monotonic() < deadline:
        after = query(project, f"""
SELECT JSON_OBJECT('activity', (SELECT JSON_OBJECT('status',status,'remaining',remaining_qty)
  FROM seckill_activity WHERE id={activity_id}),
 'batches', (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('batch_id',b.id,'activity_remaining',ab.remaining_qty,
  'activity_reserved',b.activity_reserved_qty,'available',b.available_qty,'quarantined',b.quarantined_qty,
  'expiry',b.expire_date)),JSON_ARRAY()) FROM seckill_activity_batch ab
  JOIN inventory_batch b ON b.id=ab.batch_id WHERE ab.activity_id={activity_id}),
 'release_movements', (SELECT COUNT(*) FROM stock_movement WHERE movement_type='ACTIVITY_RELEASE' AND reference_id='ACTIVITY_END:{activity_id}'),
 'release_quantity', (SELECT COALESCE(SUM(quantity),0) FROM stock_movement WHERE movement_type='ACTIVITY_RELEASE' AND reference_id='ACTIVITY_END:{activity_id}'))
""")
        if after.get("activity", {}).get("status") == "ENDED":
            break
        time.sleep(2)
    activity = after.get("activity", {})
    assert activity.get("status") == "ENDED" and int(activity.get("remaining") or 0) == 0, "activity did not end at zero stock"
    released_before = assert_activity_release(before, after)
    return {"activity_id": activity_id, "before": before, "after": after,
            "released_activity_quantity": released_before}


def render_report(project: str, results: list[dict], passed: bool,
                  failed_step: str | None = None, error: str | None = None) -> str:
    report = {"project": project, "results": results, "passed": passed}
    if failed_step is not None:
        report["failed_step"] = failed_step
    if error is not None:
        report["error"] = error
    return json.dumps(report, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="isolated Compose project name")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--cancel-order")
    parser.add_argument("--cancel-token-env", default="NPC_CANCEL_TOKEN")
    parser.add_argument("--pickup-order")
    parser.add_argument("--owner-token-env", default="NPC_OWNER_TOKEN")
    parser.add_argument("--end-activity", type=int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"(?:neighbor-pharmacy-test|npc-verify-[A-Za-z0-9_-]+|npc-load-[A-Za-z0-9_-]+)", args.project):
        parser.error("project must be the dedicated neighbor-pharmacy-test, npc-verify-* or npc-load-* project")
    target = urllib.parse.urlsplit(args.base_url)
    try:
        loopback = ipaddress.ip_address(target.hostname or "").is_loopback
    except ValueError:
        loopback = (target.hostname or "").lower() == "localhost"
    if target.scheme != "http" or not loopback:
        parser.error("base URL must use HTTP on localhost or a loopback IP")
    if not any((args.cancel_order, args.pickup_order, args.end_activity)):
        parser.error("provide at least one lifecycle fixture")
    results = []
    stage = "initialization"
    try:
        if args.cancel_order:
            stage = f"cancel:{args.cancel_order}"
            token = os.environ.get(args.cancel_token_env, "")
            if not token:
                parser.error(f"missing token environment variable {args.cancel_token_env}")
            results.append(verify_order(args.project, args.base_url, args.cancel_order, "cancel", token))
        if args.pickup_order:
            stage = f"pickup:{args.pickup_order}"
            token = os.environ.get(args.owner_token_env, "")
            if not token:
                parser.error(f"missing token environment variable {args.owner_token_env}")
            results.append(verify_order(args.project, args.base_url, args.pickup_order, "pickup", token))
        if args.end_activity:
            stage = f"end_activity:{args.end_activity}"
            results.append(verify_ended_activity(args.project, args.end_activity))
        rendered = render_report(args.project, results, True)
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        return 0
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError, ValueError, RuntimeError, AssertionError) as exc:
        rendered = render_report(args.project, results, False, stage, str(exc))
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
