#!/usr/bin/env python3
"""Verify Caffeine, Redis, Pub/Sub invalidation and TTL fallback in an isolated stack."""

import argparse
import json
import shlex
import subprocess
import sys
import time
from pathlib import Path


CHANNEL = "cache:local:invalidate:v1"
KEY_PREFIX = "pharmacy:catalog:medicines:v1:"
# CacheClient keeps local values for 30 seconds. Wait past that bound after
# dropping the Redis key so this assertion tests the TTL fallback, not timing.
LOCAL_CACHE_TTL_SECONDS = 30


def run(*args, capture=True):
    result = subprocess.run(args, check=True, text=True, capture_output=capture)
    return result.stdout.strip() if capture else ""


def compose(project, compose_files, *args):
    cmd = ["docker", "compose", "-p", project]
    for file in compose_files:
        cmd += ["-f", file]
    return run(*cmd, *args)


def container_ids(project, compose_files, service):
    output = compose(project, compose_files, "ps", "-q", service)
    return [line for line in output.splitlines() if line]


def json_from_replica(frontend_id, ip, port, path):
    raw = run("docker", "exec", frontend_id, "wget", "-qO-", "--timeout=8", f"http://{ip}:{port}{path}")
    payload = json.loads(raw)
    if isinstance(payload, dict) and payload.get("success") is False:
        raise AssertionError(f"Catalog API returned failure: {payload}")
    return payload.get("data", payload) if isinstance(payload, dict) else payload


def medicine_rows(frontend_id, ip, port, store_id):
    rows = json_from_replica(frontend_id, ip, port, f"/api/v1/stores/{store_id}/medicines")
    if not isinstance(rows, list):
        raise AssertionError(f"Expected medicine list, got {type(rows).__name__}")
    return rows


def with_name(rows, medicine_id, name):
    result = json.loads(json.dumps(rows))
    for row in result:
        if int(row["id"]) == medicine_id:
            row["name"] = name
            return result
    raise AssertionError(f"Medicine {medicine_id} is not visible in store catalog")


def assert_catalog_name(rows, medicine_id, expected, replica):
    """Fail with enough context to diagnose a cache replica's stale value."""
    actual = catalog_name(rows, medicine_id)
    if actual != expected:
        raise AssertionError(
            f"replica {replica} returned medicine {medicine_id} name {actual!r}; "
            f"expected {expected!r}"
        )


def catalog_name(rows, medicine_id):
    """Return a catalog name without raising an opaque StopIteration on missing rows."""
    row = next((row for row in rows if int(row.get("id", -1)) == medicine_id), None)
    return row.get("name") if row is not None else None


def verify_local_and_redis_hit(fetch, write_redis_value, medicine_id, probe_a, probe_b):
    """Prime only replica A locally, then prove replica B reads the newer Redis value."""
    write_redis_value(probe_a)
    first_a = fetch("a")
    assert_catalog_name(first_a, medicine_id, probe_a, "A (initial Redis read)")

    # Do not read from replica B before changing Redis. Otherwise B would also
    # cache probe_a and the later assertion could not distinguish a Redis hit.
    write_redis_value(probe_b)
    cached_a = fetch("a")
    redis_b = fetch("b")
    assert_catalog_name(cached_a, medicine_id, probe_a, "A (Caffeine hit)")
    assert_catalog_name(redis_b, medicine_id, probe_b, "B (Redis hit)")


def redis(project, compose_files, *args):
    return compose(project, compose_files, "exec", "-T", "redis", "redis-cli", "--raw", *args)


def invalidate_without_pubsub(redis_call, key, version_key):
    """Advance the cache generation and drop Redis data without notifying replicas."""
    version = redis_call("INCR", version_key)
    redis_call("PEXPIRE", version_key, "172800000")
    redis_call("DEL", key)
    return version


def cleanup_cache_probe(redis_call, key, version_key):
    """Restore shared cache state while attempting every cleanup operation."""
    errors = []

    def describe(action, error):
        detail = getattr(error, "stderr", None) or getattr(error, "stdout", None) or str(error)
        return f"{action}: {type(error).__name__}: {str(detail).strip()}"

    try:
        redis_call("DEL", key)
    except Exception as error:
        errors.append(describe("delete probe key", error))
    try:
        current_version = redis_call("GET", version_key)
    except Exception as error:
        errors.append(describe("read cache version", error))
        current_version = ""
    if current_version:
        try:
            redis_call("PUBLISH", CHANNEL, key + "\n" + current_version)
        except Exception as error:
            errors.append(describe("publish cache invalidation", error))
    return errors


def medicine_name(project, compose_files, store_id, medicine_id):
    sql = ("SELECT name FROM pharmacy_medicine WHERE id={medicine_id} AND store_id={store_id} "
           "AND enabled=TRUE AND sale_category IN ('OTC','MEDICAL_SUPPLY')").format(
               medicine_id=medicine_id, store_id=store_id)
    # The mysql CLI defaults to latin1 and replaces CJK characters with '?';
    # read catalog names as utf8mb4 so the expected name is never mojibake.
    output = compose(project, compose_files, "exec", "-T", "mysql", "sh", "-lc",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 '
                     '--batch --skip-column-names -uroot hmdp -e ' +
                     shlex.quote(sql))
    if not output:
        raise AssertionError("the supplied medicine is not a legal visible catalog entry")
    return output.splitlines()[0]


def wait_for_backends(frontend_id, backend_ids, ips, port, timeout=240):
    """Wait for both Spring applications, not merely running containers."""
    deadline = time.monotonic() + timeout
    last_errors = {}
    while time.monotonic() < deadline:
        ready = True
        for ip in ips:
            try:
                payload = json.loads(run("docker", "exec", frontend_id, "wget", "-qO-",
                                         "--timeout=3", f"http://{ip}:{port}/v3/api-docs"))
                if not isinstance(payload.get("paths"), dict) or not payload["paths"]:
                    raise AssertionError("OpenAPI paths are not ready")
                last_errors.pop(ip, None)
            except (subprocess.CalledProcessError, json.JSONDecodeError, AssertionError) as error:
                ready = False
                last_errors[ip] = str(error)
        if ready:
            return
        time.sleep(2)
    diagnostics = {}
    for backend_id, ip in zip(backend_ids, ips):
        try:
            log_output = run("docker", "logs", "--tail=80", backend_id)
        except subprocess.CalledProcessError as error:
            log_output = f"docker logs failed: {error}"
        diagnostics[ip] = {"last_readiness_error": last_errors.get(ip), "backend_logs": log_output}
    try:
        frontend_logs = run("docker", "logs", "--tail=120", frontend_id)
    except subprocess.CalledProcessError as error:
        output = (error.stdout or "") + (error.stderr or "")
        frontend_logs = f"docker logs failed: {output[-4000:]}"
    raise AssertionError(
        f"backend replicas did not become ready within {timeout}s: "
        f"replicas={diagnostics}; frontend_logs={frontend_logs}"
    )


def stack_startup_diagnostics(project, compose_files):
    """Return Compose state and backend/frontend logs for replica startup failures."""
    diagnostics = {}
    for label, args in (
        ("containers", ("ps", "-a")),
        ("backend_logs", ("logs", "--tail=120", "backend")),
        ("frontend_logs", ("logs", "--tail=120", "frontend")),
    ):
        try:
            diagnostics[label] = compose(project, compose_files, *args)
        except (OSError, subprocess.SubprocessError) as error:
            output = (getattr(error, "stdout", None) or "") + (getattr(error, "stderr", None) or "")
            detail = output[-4000:] or str(error)
            diagnostics[label] = f"compose {' '.join(args)} failed: {detail}"
    return diagnostics


def capture_compose_logs(project, compose_files, services, tail=120):
    """Collect each service log independently without hiding the primary failure."""
    diagnostics = {}
    for service in services:
        try:
            diagnostics[service] = compose(
                project, compose_files, "logs", "--tail", str(tail), service)
        except (OSError, subprocess.SubprocessError) as error:
            output = (getattr(error, "stdout", None) or "") + (getattr(error, "stderr", None) or "")
            detail = output[-4000:] or str(error)
            diagnostics[service] = f"unable to read {service} logs: {type(error).__name__}: {detail}"
    return diagnostics


def probe_login_code_route(frontend_id):
    """Exercise the exact Nginx-to-Spring route that failed with HTTP 502 in Run 4."""
    result = subprocess.run(
        ["docker", "exec", frontend_id, "wget", "-S", "-O-", "--timeout=8", "--post-data=",
         "http://127.0.0.1/user/code?phone=19900000001"],
        check=False, text=True, capture_output=True, timeout=12,
    )
    raw = result.stdout.strip()
    if result.returncode != 0:
        response_headers = result.stderr.strip()[-2000:]
        response_body = raw[:1000]
        raise AssertionError(
            "POST /user/code through Nginx failed "
            f"(wget exit {result.returncode}); response headers/transport: {response_headers!r}; "
            f"response body: {response_body!r}"
        )
    try:
        envelope = json.loads(raw)
    except json.JSONDecodeError as error:
        raise AssertionError(
            f"POST /user/code returned non-JSON response: {raw[:1000]!r}; "
            f"wget diagnostics: {result.stderr.strip()[-2000:]!r}"
        ) from error
    data = envelope.get("data")
    if envelope.get("success") is not True or not isinstance(data, dict) or not data.get("code"):
        raise AssertionError(
            f"POST /user/code did not return an exposed test code: {envelope!r}; "
            f"wget diagnostics: {result.stderr.strip()[-2000:]!r}"
        )


def probe_bloom_allows_shop(frontend_id, ips, port, shop_id):
    """Query a seeded legal shop directly on each replica through Bloom-backed code."""
    for ip in ips:
        raw = run("docker", "exec", frontend_id, "wget", "-qO-", "--timeout=8",
                  f"http://{ip}:{port}/shop/{shop_id}")
        try:
            envelope = json.loads(raw)
        except json.JSONDecodeError as error:
            raise AssertionError(
                f"Bloom legal-shop probe on backend {ip} returned non-JSON: {raw[:500]!r}"
            ) from error
        data = envelope.get("data") if isinstance(envelope, dict) else None
        if (not isinstance(envelope, dict) or envelope.get("success") is not True
                or not isinstance(data, dict) or int(data.get("id", -1)) != shop_id):
            raise AssertionError(
                f"Bloom filter or shop lookup rejected legal shop {shop_id} on backend {ip}: "
                f"{envelope!r}"
            )


def probe_backend_login_code_routes(frontend_id, ips, port):
    """Compare direct backend responses with the Nginx probe after a route failure."""
    results = {}
    for ip in ips:
        try:
            result = subprocess.run(
                ["docker", "exec", frontend_id, "wget", "-S", "-O-", "--timeout=8", "--post-data=",
                 f"http://{ip}:{port}/user/code?phone=19900000001"],
                check=False, text=True, capture_output=True, timeout=12,
            )
            results[ip] = {
                "exit_code": result.returncode,
                "response_headers_or_transport": result.stderr.strip()[-2000:],
                "response_body": result.stdout.strip()[:1000],
            }
        except (OSError, subprocess.SubprocessError) as error:
            results[ip] = {"probe_error": f"{type(error).__name__}: {error}"}
    return results


def write_report(output, project, store_id, medicine_id, checks, passed, error=None,
                 diagnostics=None, stage=None):
    report = {"project": project, "store_id": store_id, "medicine_id": medicine_id,
              "checks": checks, "passed": passed,
              "note": "No MySQL business rows were modified."}
    if stage is not None:
        report["stage"] = stage
    if error is not None:
        report["error"] = str(error)
    if diagnostics is not None:
        report["diagnostics"] = diagnostics
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if output:
        with open(output, "w", encoding="utf-8") as target:
            target.write(rendered)
    return rendered


def clear_previous_report(output):
    """Prevent a failed startup from leaving a prior run's JSON as evidence."""
    if output:
        try:
            Path(output).unlink(missing_ok=True)
        except OSError as error:
            raise RuntimeError(f"cannot remove previous cache report {output}: {error}") from error


def write_startup_failure(output, project, store_id, medicine_id, error, diagnostics):
    message = f"cache verification startup failed: {type(error).__name__}: {error}"
    rendered = write_report(output, project, store_id, medicine_id, {}, False,
                            message, diagnostics, "stack_startup")
    print(rendered, end="", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="isolated Compose project (npc-verify-* only)")
    parser.add_argument("--store-id", required=True, type=int)
    parser.add_argument("--medicine-id", required=True, type=int)
    parser.add_argument("--backend-port", type=int, default=8081,
                        help="backend's internal HTTP port (default: application.yml's 8081)")
    parser.add_argument("--compose-file", default="deploy/server-test.compose.yml")
    parser.add_argument("--override-file", default="deploy/verify/cache-invalidation.compose.override.yml")
    parser.add_argument("--output")
    args = parser.parse_args()
    if not args.project.startswith("npc-verify-cache-"):
        parser.error("refusing non-isolated project; use a disposable npc-verify-cache-* project")
    if not 1 <= args.backend_port <= 65535:
        parser.error("backend port must be between 1 and 65535")
    files = [args.compose_file, args.override_file]
    clear_previous_report(args.output)
    try:
        frontend = container_ids(args.project, files, "frontend")
        replicas = container_ids(args.project, files, "backend")
        if len(frontend) != 1 or len(replicas) != 2:
            raise AssertionError(
                "stack must have exactly one frontend and two backend replicas "
                f"(found frontend={len(frontend)}, backend={len(replicas)})"
            )
        networks = json.loads(run("docker", "inspect", frontend[0], "--format", "{{json .NetworkSettings.Networks}}"))
        network_name = next(iter(networks))
        replicas_by_ip = {}
        for replica in replicas:
            nets = json.loads(run("docker", "inspect", replica, "--format", "{{json .NetworkSettings.Networks}}"))
            replicas_by_ip[nets[network_name]["IPAddress"]] = replica
        ips = sorted(replicas_by_ip)
        a, b = ips
        wait_for_backends(frontend[0], [replicas_by_ip[ip] for ip in ips], ips, args.backend_port)
    except Exception as error:
        diagnostics = stack_startup_diagnostics(args.project, files)
        write_startup_failure(args.output, args.project, args.store_id, args.medicine_id,
                              error, diagnostics)
        raise AssertionError(f"cache verification startup failed: {error}; diagnostics={diagnostics}") from error
    key = KEY_PREFIX + str(args.store_id)
    version_key = "cache:version:v1:" + key
    nonce = f"cache-probe-{int(time.time())}"
    probe_a, probe_b = nonce + "-caffeine", nonce + "-redis"
    checks = {}
    stage = "login_code_route"
    failure = None
    diagnostics = {}
    try:
        probe_login_code_route(frontend[0])
        checks["login_code_route_ready"] = True
        stage = "bloom_legal_shop"
        # The 005 demo seed inserts shop 900010 before each backend's
        # BloomFilterInitializer snapshots tb_shop. Exercise the actual
        # Bloom-backed /shop/{id} path on both replicas; catalog requests do
        # not use this Bloom filter and cannot prove false-negative safety.
        probe_bloom_allows_shop(frontend[0], ips, args.backend_port, 900010)
        checks["bloom_allows_legal_shop_on_both_replicas"] = True
        stage = "catalog_source"
        original_name = medicine_name(args.project, files, args.store_id, args.medicine_id)
        original = [{"id": args.medicine_id, "name": original_name}]
        script = ("local v=redis.call('INCR',KEYS[1]); redis.call('PEXPIRE',KEYS[1],172800000); "
                  "redis.call('DEL',KEYS[2]); redis.call('PUBLISH',ARGV[1],KEYS[2]..'\\n'..v); return v")
        redis(args.project, files, "EVAL", script, "2", version_key, key, CHANNEL)
        time.sleep(1)
        def fetch(replica):
            ip = a if replica == "a" else b
            return medicine_rows(frontend[0], ip, args.backend_port, args.store_id)

        def write_redis_value(name):
            value = json.dumps(with_name(original, args.medicine_id, name), ensure_ascii=False)
            redis(args.project, files, "SET", key, value, "EX", "60")

        stage = "caffeine_and_redis_reads"
        verify_local_and_redis_hit(fetch, write_redis_value, args.medicine_id, probe_a, probe_b)
        checks["caffeine_hit_avoids_redis"] = True
        checks["redis_hit_avoids_mysql"] = True

        # Publish the same versioned invalidation produced by CacheClient.delete.
        stage = "pubsub_invalidation"
        redis(args.project, files, "EVAL", script, "2", version_key, key, CHANNEL)
        redis(args.project, files, "SET", key,
              json.dumps(with_name(original, args.medicine_id, nonce + "-pubsub"), ensure_ascii=False), "EX", "60")
        deadline = time.monotonic() + 12
        while True:
            names = []
            for ip in (a, b):
                rows = medicine_rows(frontend[0], ip, args.backend_port, args.store_id)
                names.append(catalog_name(rows, args.medicine_id))
            if names == [nonce + "-pubsub", nonce + "-pubsub"]:
                break
            if time.monotonic() >= deadline:
                raise AssertionError(
                    "Pub/Sub did not evict both local entries for medicine "
                    f"{args.medicine_id}: expected {[nonce + '-pubsub'] * 2!r}, actual {names!r} "
                    f"from replicas {a} and {b}"
                )
            time.sleep(0.25)
        checks["pubsub_invalidates_both_replicas"] = True

        # Simulate a dropped notification. First seed the sentinel into both
        # local caches, then advance the generation and delete Redis data
        # without PUBLISH. Both replicas should keep serving the stale local
        # value only until its bounded TTL expires; the next read must fall
        # through Redis to authoritative MySQL.
        stage = "ttl_fallback"
        redis(args.project, files, "EVAL", script, "2", version_key, key, CHANNEL)
        time.sleep(0.5)
        redis(args.project, files, "SET", key,
              json.dumps(with_name(original, args.medicine_id, probe_a), ensure_ascii=False), "EX", "60")
        for label, ip in (("A", a), ("B", b)):
            rows = medicine_rows(frontend[0], ip, args.backend_port, args.store_id)
            assert_catalog_name(rows, args.medicine_id, probe_a,
                                f"{label} at {ip} (sentinel cached before lost notification)")
        invalidate_without_pubsub(
            lambda *command: redis(args.project, files, *command), key, version_key)
        for label, ip in (("A", a), ("B", b)):
            rows = medicine_rows(frontend[0], ip, args.backend_port, args.store_id)
            assert_catalog_name(rows, args.medicine_id, probe_a,
                                f"{label} at {ip} (stale local value before TTL fallback)")
        checks["lost_pubsub_stale_value_is_ttl_bounded"] = True
        time.sleep(LOCAL_CACHE_TTL_SECONDS + 5)
        for label, ip in (("A", a), ("B", b)):
            rows = medicine_rows(frontend[0], ip, args.backend_port, args.store_id)
            assert_catalog_name(rows, args.medicine_id, original_name,
                                f"{label} at {ip} (MySQL fallback after local TTL)")
        checks["lost_pubsub_ttl_falls_back_to_mysql"] = True
    except Exception as error:
        # A proxy 502 or backend restart otherwise leaves only the caller's
        # status code; compare direct backend responses and capture both
        # backend replicas before stack teardown.
        diagnostics = {}
        diagnostics["direct_backend_login_code_probes"] = probe_backend_login_code_routes(
            frontend[0], ips, args.backend_port)
        diagnostics.update(capture_compose_logs(
            args.project, files, ("backend", "frontend")))
        failure = error
        stage = f"{stage}_verification"
    finally:
        # Restore the shared key from its authoritative source and clear the
        # version marker so later test runs see the original catalog. Cleanup
        # is best-effort, but any failure must be visible in this run's report.
        cleanup_errors = cleanup_cache_probe(
            lambda *command: redis(args.project, files, *command), key, version_key)
        if cleanup_errors:
            diagnostics["cleanup_errors"] = cleanup_errors
            diagnostics.update(capture_compose_logs(
                args.project, files, ("backend", "frontend", "redis")))
            cleanup_error = "; ".join(cleanup_errors)
            if failure is not None:
                cleanup_error = f"{failure}; cleanup also failed: {cleanup_error}"
            failure_report = write_report(args.output, args.project, args.store_id,
                                          args.medicine_id, checks, False, cleanup_error,
                                          diagnostics, "cleanup")
            print(failure_report, end="", file=sys.stderr)
            failure = AssertionError(cleanup_error)

    if failure is not None:
        if not cleanup_errors:
            failure_report = write_report(args.output, args.project, args.store_id,
                                          args.medicine_id, checks, False, failure,
                                          diagnostics, stage)
            print(failure_report, end="", file=sys.stderr)
        raise AssertionError(f"cache verification failed: {failure}") from failure

    report_passed = all(checks.values())
    print(write_report(args.output, args.project, args.store_id, args.medicine_id,
                       checks, report_passed, stage="completed"), end="")
    return 0 if report_passed else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, subprocess.CalledProcessError, KeyError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
