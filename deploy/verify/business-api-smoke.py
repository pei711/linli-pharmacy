#!/usr/bin/env python3
"""Exercise the authenticated pharmacy workflow against an isolated test stack."""

from __future__ import annotations

import argparse
import ipaddress
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None

    def call(self, method: str, path: str, body: dict | None = None, extra_headers: dict | None = None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = self.token
        if extra_headers:
            headers.update(extra_headers)
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        auth_state = "authenticated" if self.token else "anonymous"
        print(f"SMOKE request start: {method} {path} ({auth_state})", flush=True)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                status = getattr(response, "status", None)
                if status is None:
                    status = response.getcode() if hasattr(response, "getcode") else "unknown"
                payload = json.loads(response.read())
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:500]
            auth_hint = (
                "; verify the public /user/code route, login response token, and Nginx "
                "Authorization forwarding"
                if exc.code == 401 else ""
            )
            raise RuntimeError(
                f"{method} {path} returned HTTP {exc.code} ({auth_state} request){auth_hint}: {detail}"
            ) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise RuntimeError(
                f"{method} {path} transport failure ({auth_state} request): {exc}"
            ) from exc
        if not payload.get("success"):
            raise RuntimeError(f"{method} {path} failed: {payload.get('errorMsg')}")
        print(f"SMOKE request passed: {method} {path} HTTP {status}", flush=True)
        return payload.get("data")


def assert_unauthenticated_profile_is_rejected(base_url: str) -> None:
    request = urllib.request.Request(base_url.rstrip("/") + "/user/me", method="GET")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raise RuntimeError(
                f"anonymous GET /user/me unexpectedly returned HTTP {response.status}"
            )
    except urllib.error.HTTPError as exc:
        if exc.code != 401:
            detail = exc.read().decode("utf-8", "replace")[:500]
            raise RuntimeError(f"anonymous GET /user/me returned HTTP {exc.code}, expected 401: {detail}") from exc
        print("SMOKE auth precheck passed: anonymous GET /user/me returned HTTP 401", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--store-id", type=int, default=900010)
    parser.add_argument("--phone", default="19900000001")
    args = parser.parse_args()
    parsed_url = urllib.parse.urlsplit(args.base_url)
    try:
        is_loopback = parsed_url.hostname in {"localhost", "127.0.0.1", "::1"} or ipaddress.ip_address(
            parsed_url.hostname or ""
        ).is_loopback
    except ValueError:
        is_loopback = False
    if parsed_url.scheme != "http" or not is_loopback:
        parser.error("base URL must use HTTP on localhost/loopback; this script mutates test data")
    api = Api(args.base_url)

    print("SMOKE auth precheck: protected profile rejects anonymous request", flush=True)
    assert_unauthenticated_profile_is_rejected(args.base_url)
    print("SMOKE auth step 1/3: request isolated login code", flush=True)
    code = api.call("POST", "/user/code?" + urllib.parse.urlencode({"phone": args.phone}))
    if not isinstance(code, dict) or not code.get("code"):
        raise RuntimeError("isolated test stack did not expose the login code")
    print("SMOKE auth step 2/3: exchange code for token", flush=True)
    api.token = api.call("POST", "/user/login", {"phone": args.phone, "code": code["code"]})
    if not isinstance(api.token, str) or not api.token:
        raise RuntimeError("login did not return an authorization token")
    print("SMOKE auth step 3/3: verify token at protected profile endpoint", flush=True)
    profile = api.call("GET", "/user/me")
    if not profile or not profile.get("id"):
        raise RuntimeError("authenticated profile is missing")

    suffix = uuid.uuid4().hex[:12]
    medicine_id = api.call("POST", "/api/v1/admin/medicines", {
        "storeId": args.store_id,
        "name": f"隔离冒烟商品-{suffix}",
        "specification": "10片/盒",
        "saleCategory": "OTC",
    })
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
    api.call("POST", "/api/v1/admin/inventory/batches", {
        "storeId": args.store_id,
        "medicineId": medicine_id,
        "batchNo": f"SMOKE-{suffix}",
        "productionDate": today.isoformat(),
        "expireDate": (today + timedelta(days=180)).isoformat(),
        "quantity": 2,
    })
    now = datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None)
    start_at = now + timedelta(seconds=8)
    end_at = now + timedelta(minutes=5)
    activity_id = api.call("POST", "/api/v1/admin/activities", {
        "storeId": args.store_id,
        "medicineId": medicine_id,
        "title": f"隔离冒烟活动-{suffix}",
        "quantity": 1,
        "perUserLimit": 1,
        "startAt": start_at.isoformat(timespec="seconds"),
        "endAt": end_at.isoformat(timespec="seconds"),
    })
    api.call("POST", f"/api/v1/admin/activities/{activity_id}/publish")
    delay = (start_at - datetime.now(ZoneInfo("Asia/Shanghai")).replace(tzinfo=None)).total_seconds()
    if delay > 0:
        time.sleep(delay + 0.2)

    request_id = str(uuid.uuid4())
    # Keep the idempotency key stable so the request can be audited in MySQL.
    api.call("POST", f"/api/v1/activities/{activity_id}/reserve", {"quantity": 1},
             {"Idempotency-Key": request_id})

    result = None
    for _ in range(60):
        result = api.call("GET", f"/api/v1/seckill/requests/{request_id}")
        if result and result.get("status") in {"SUCCESS", "FAILED", "EXPIRED"}:
            break
        time.sleep(1)
    if not result or result.get("status") != "SUCCESS" or not result.get("orderNo"):
        raise RuntimeError(f"reservation did not become SUCCESS: {result}")
    order_no = result["orderNo"]
    order = api.call("GET", f"/api/v1/orders/{order_no}")
    if not order or order.get("status") != "RESERVED":
        raise RuntimeError(f"order lookup did not return RESERVED: {order}")
    api.call("POST", f"/api/v1/admin/orders/{order_no}/pickup")
    inventory = api.call("GET", f"/api/v1/admin/inventory/fefo?storeId={args.store_id}&medicineId={medicine_id}")
    batch = next((row for row in inventory if row.get("batch_no") == f"SMOKE-{suffix}"), None)
    if not batch or int(batch.get("available_qty", -1)) != 1:
        raise RuntimeError(f"FEFO inventory did not reflect pickup: {batch}")
    final_order = api.call("GET", f"/api/v1/orders/{order_no}")
    if final_order.get("status") != "COMPLETED":
        raise RuntimeError(f"pickup did not complete order: {final_order}")

    print(json.dumps({
        "passed": True,
        "checks": {
            "owner_login": True,
            "medicine_and_batch_created": True,
            "activity_published": True,
            "reservation_consumed": True,
            "order_lookup": True,
            "pickup_completed": True,
            "remaining_available_qty": 1,
        },
        "fixture": {"store_id": args.store_id, "medicine_id": medicine_id,
                    "activity_id": activity_id, "request_id": request_id, "order_no": order_no},
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
