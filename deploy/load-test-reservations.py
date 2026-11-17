#!/usr/bin/env python3
"""Concurrent reservation load driver for an isolated Neighbor Pharmacy test stack."""

from __future__ import annotations

import argparse
import concurrent.futures
import csv
import ipaddress
import json
import math
import statistics
import sys
import time
import uuid
from collections import Counter
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def load_users(path: Path, count: int) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as source:
        rows = list(csv.DictReader(source))
    if len(rows) < count:
        raise ValueError(f"token CSV has {len(rows)} users; {count} required")
    users = rows[:count]
    if any(not row.get("token", "").strip() for row in users):
        raise ValueError("every selected CSV row must have a non-empty token column")
    tokens = [row["token"].strip() for row in users]
    if len(set(tokens)) != len(tokens):
        raise ValueError("selected CSV rows must contain distinct user tokens")
    return users


def ensure_test_target(base_url: str, allow_remote: bool) -> None:
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("base URL must be an absolute http(s) URL")
    host = parsed.hostname.lower()
    local = host in {"localhost", "127.0.0.1", "::1"}
    try:
        local = local or ipaddress.ip_address(host).is_loopback
    except ValueError:
        pass
    if not local and not allow_remote:
        raise ValueError("non-loopback target refused; use --allow-remote-test-target only for an isolated test stack")


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)] if ordered else 0.0


def reserve(base_url: str, activity_id: int, user: dict[str, str], timeout: float) -> dict:
    started = time.perf_counter()
    request_id = str(uuid.uuid4())
    headers = {
        "Authorization": user["token"].strip(),
        "Idempotency-Key": request_id,
        "Content-Type": "application/json",
    }
    ip = user.get("ip", "").strip()
    if ip:
        headers["X-Real-IP"] = ip
    request = Request(
        f"{base_url.rstrip('/')}/api/v1/activities/{activity_id}/reserve",
        data=b'{"quantity":1}',
        headers=headers,
        method="POST",
    )
    http_status = 0
    business_status = "NO_RESPONSE"
    error = None
    try:
        with urlopen(request, timeout=timeout) as response:
            http_status = response.status
            payload = json.loads(response.read().decode("utf-8"))
        data = payload.get("data") if isinstance(payload, dict) else None
        business_status = data.get("status", "MISSING_STATUS") if isinstance(data, dict) else "MISSING_DATA"
        if payload.get("success") is False:
            business_status = "API_FAILURE"
    except HTTPError as exc:
        http_status = exc.code
        try:
            payload = json.loads(exc.read().decode("utf-8"))
            error = payload.get("errorMsg") if isinstance(payload, dict) else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            error = "non-JSON HTTP error"
        business_status = "HTTP_ERROR"
    except (URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
        error = type(exc).__name__
    return {
        "http_status": http_status,
        "business_status": str(business_status),
        "latency_ms": (time.perf_counter() - started) * 1000,
        "error_type": error,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--activity-id", required=True, type=int)
    parser.add_argument("--tokens-csv", required=True, type=Path,
                        help="UTF-8 CSV with token and optional ip columns; keep this file private")
    parser.add_argument("--users", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=200)
    parser.add_argument("--timeout-seconds", type=float, default=15)
    parser.add_argument("--output", type=Path, default=Path("reservation-load-report.json"))
    parser.add_argument("--allow-remote-test-target", action="store_true",
                        help="required when the isolated test stack is not on loopback")
    args = parser.parse_args()

    try:
        ensure_test_target(args.base_url, args.allow_remote_test_target)
        if args.users < 1 or args.concurrency < 1 or args.timeout_seconds <= 0 or args.activity_id < 1:
            raise ValueError("users, concurrency, activity ID, and timeout must be positive")
        users = load_users(args.tokens_csv, args.users)
        started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(args.concurrency, args.users)) as pool:
            results = list(pool.map(
                lambda user: reserve(args.base_url, args.activity_id, user, args.timeout_seconds), users
            ))
        elapsed = time.perf_counter() - started
        latencies = [result["latency_ms"] for result in results]
        report = {
            "target": args.base_url,
            "activity_id": args.activity_id,
            "offered_users": len(results),
            "configured_concurrency": min(args.concurrency, args.users),
            "elapsed_seconds": round(elapsed, 3),
            "throughput_requests_per_second": round(len(results) / elapsed, 2) if elapsed else 0,
            "http_status_counts": dict(Counter(str(item["http_status"]) for item in results)),
            "business_status_counts": dict(Counter(item["business_status"] for item in results)),
            "latency_ms": {
                "min": round(min(latencies), 2) if latencies else 0,
                "mean": round(statistics.fmean(latencies), 2) if latencies else 0,
                "p50": round(percentile(latencies, 0.50), 2),
                "p95": round(percentile(latencies, 0.95), 2),
                "p99": round(percentile(latencies, 0.99), 2),
                "max": round(max(latencies), 2) if latencies else 0,
            },
            "transport_errors": dict(Counter(item["error_type"] for item in results if item["error_type"])),
            "credential_values_recorded": False,
            "completed_responses": sum(1 for item in results if item["http_status"] > 0),
            "note": "Request-level results only; reconcile final MySQL orders and inventory before interpreting stock correctness.",
        }
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if not report["transport_errors"] and report["completed_responses"] == len(results) else 2
    except (OSError, ValueError, csv.Error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
