import importlib.util
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "cache-invalidation.py"
SPEC = importlib.util.spec_from_file_location("cache_invalidation", MODULE_PATH)
cache = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cache)


class CacheInvalidationTest(unittest.TestCase):
    def test_cache_probe_cleanup_attempts_all_steps_and_returns_diagnostics(self):
        calls = []

        def redis_call(*command):
            calls.append(command)
            if command == ("DEL", "catalog-key"):
                raise cache.subprocess.CalledProcessError(1, command, stderr="redis unavailable")
            if command == ("GET", "version-key"):
                return "17"
            if command[0] == "PUBLISH":
                raise cache.subprocess.CalledProcessError(1, command, stderr="publish failed")
            raise AssertionError(command)

        errors = cache.cleanup_cache_probe(redis_call, "catalog-key", "version-key")

        self.assertEqual(calls, [
            ("DEL", "catalog-key"), ("GET", "version-key"),
            ("PUBLISH", cache.CHANNEL, "catalog-key\n17"),
        ])
        self.assertEqual(len(errors), 2)
        self.assertIn("delete probe key", errors[0])
        self.assertIn("redis unavailable", errors[0])
        self.assertIn("publish cache invalidation", errors[1])
        self.assertIn("publish failed", errors[1])

    def test_replica_assertion_reports_expected_and_actual_catalog_values(self):
        with self.assertRaisesRegex(
                AssertionError,
                "replica B at 172\\.20\\.0\\.3.*'stale-value'.*'mysql-value'"):
            cache.assert_catalog_name(
                [{"id": 900011, "name": "stale-value"}],
                900011, "mysql-value", "B at 172.20.0.3 (MySQL fallback after local TTL)")

    def test_replica_assertion_reports_missing_medicine(self):
        with self.assertRaisesRegex(AssertionError, "medicine 900011 name None"):
            cache.assert_catalog_name([], 900011, "mysql-value", "A")

    def test_pubsub_catalog_lookup_returns_missing_value_for_contextual_failure(self):
        self.assertIsNone(cache.catalog_name([], 900011))
        self.assertEqual(cache.catalog_name(
            [{"id": 900011, "name": "updated"}], 900011), "updated")

    def test_lost_pubsub_waits_past_production_local_cache_ttl(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn("LOCAL_CACHE_TTL_SECONDS = 30", source)
        self.assertIn("time.sleep(LOCAL_CACHE_TTL_SECONDS + 5)", source)
        self.assertIn("invalidate_without_pubsub(", source)

    def test_lost_pubsub_advances_version_and_deletes_cache_without_publishing(self):
        calls = []

        def redis_call(*command):
            calls.append(command)
            return "18"

        version = cache.invalidate_without_pubsub(redis_call, "catalog-key", "version-key")

        self.assertEqual(version, "18")
        self.assertEqual(calls, [
            ("INCR", "version-key"),
            ("PEXPIRE", "version-key", "172800000"),
            ("DEL", "catalog-key"),
        ])
        self.assertFalse(any(command[0] == "PUBLISH" for command in calls))

    def test_collects_compose_state_and_logs_when_replica_count_is_wrong(self):
        with patch.object(cache, "compose", side_effect=[
            "backend-1\nfrontend-1",
            "backend startup failed",
            "frontend is waiting for upstream",
        ]) as compose:
            diagnostics = cache.stack_startup_diagnostics(
                "npc-verify-cache-test", ["stack.yml", "cache.yml"])

        self.assertEqual(diagnostics, {
            "containers": "backend-1\nfrontend-1",
            "backend_logs": "backend startup failed",
            "frontend_logs": "frontend is waiting for upstream",
        })
        self.assertEqual(compose.call_count, 3)

    def test_startup_diagnostics_keep_compose_errors(self):
        error = cache.subprocess.CalledProcessError(
            1, ["docker", "compose", "logs"], stderr="daemon unavailable")
        with patch.object(cache, "compose", side_effect=error):
            diagnostics = cache.stack_startup_diagnostics("npc-verify-cache-test", [])

        self.assertIn("daemon unavailable", diagnostics["containers"])
        self.assertIn("daemon unavailable", diagnostics["backend_logs"])
        self.assertIn("daemon unavailable", diagnostics["frontend_logs"])

    def test_startup_diagnostics_keep_missing_docker_binary_error(self):
        with patch.object(cache, "compose", side_effect=FileNotFoundError("docker not installed")):
            diagnostics = cache.stack_startup_diagnostics("npc-verify-cache-test", [])

        self.assertIn("docker not installed", diagnostics["containers"])
        self.assertIn("docker not installed", diagnostics["backend_logs"])
        self.assertIn("docker not installed", diagnostics["frontend_logs"])

    def test_compose_log_capture_keeps_each_failure_and_continues(self):
        with patch.object(cache, "compose", side_effect=[
                FileNotFoundError("docker CLI unavailable"),
                "frontend logs are readable"]):
            diagnostics = cache.capture_compose_logs(
                "npc-verify-cache-test", [], ("backend", "frontend"))

        self.assertIn("docker CLI unavailable", diagnostics["backend"])
        self.assertEqual(diagnostics["frontend"], "frontend logs are readable")

    def test_backend_readiness_failure_collects_backend_and_frontend_logs(self):
        def fake_run(*args, **kwargs):
            if args[-1] == "172.20.0.2:8081/v3/api-docs":
                raise cache.subprocess.CalledProcessError(1, args, stderr="connection refused")
            if args[:3] == ("docker", "logs", "--tail=80"):
                return "backend startup log"
            if args[:3] == ("docker", "logs", "--tail=120"):
                return "nginx upstream log"
            raise AssertionError(args)

        with patch.object(cache, "run", side_effect=fake_run), \
                patch.object(cache.time, "monotonic", side_effect=[0, 1, 2]), \
                patch.object(cache.time, "sleep"):
            with self.assertRaisesRegex(AssertionError, "nginx upstream log") as error:
                cache.wait_for_backends("frontend-1", ["backend-1"], ["172.20.0.2"], 8081, timeout=1)

        self.assertIn("backend startup log", str(error.exception))

    def test_probes_the_login_code_route_through_nginx(self):
        response = type("Completed", (), {
            "returncode": 0, "stdout": '{"success":true,"data":{"code":"123456"}}',
            "stderr": "HTTP/1.1 200 OK",
        })()
        with patch.object(cache.subprocess, "run", return_value=response) as run:
            cache.probe_login_code_route("frontend-container")

        command = run.call_args.args[0]
        self.assertIn("frontend-container", command)
        self.assertIn("--post-data=", command)
        self.assertIn("-S", command)
        self.assertTrue(run.call_args.kwargs["check"] is False)
        self.assertEqual(run.call_args.kwargs["timeout"], 12)
        self.assertIn("http://127.0.0.1/user/code?phone=19900000001", command)

    def test_reports_http_502_details_for_login_code_route(self):
        response = type("Completed", (), {
            "returncode": 8, "stdout": "<html>502 Bad Gateway</html>",
            "stderr": "HTTP/1.1 502 Bad Gateway\\nConnection refused",
        })()
        with patch.object(cache.subprocess, "run", return_value=response):
            with self.assertRaisesRegex(AssertionError, "502 Bad Gateway") as error:
                cache.probe_login_code_route("frontend-container")
        self.assertIn("Connection refused", str(error.exception))

    def test_reports_non_json_response_body(self):
        response = type("Completed", (), {
            "returncode": 0, "stdout": "upstream returned HTML", "stderr": "HTTP/1.1 200 OK",
        })()
        with patch.object(cache.subprocess, "run", return_value=response):
            with self.assertRaisesRegex(AssertionError, "non-JSON response"):
                cache.probe_login_code_route("frontend-container")

    def test_bloom_probe_reaches_legal_shop_on_each_backend(self):
        responses = [
            '{"success":true,"data":{"id":900010,"name":"Demo pharmacy"}}',
            '{"success":true,"data":{"id":900010,"name":"Demo pharmacy"}}',
        ]
        with patch.object(cache, "run", side_effect=responses) as run:
            cache.probe_bloom_allows_shop(
                "frontend-container", ["172.20.0.2", "172.20.0.3"], 8081, 900010)

        self.assertEqual(run.call_count, 2)
        self.assertIn("http://172.20.0.2:8081/shop/900010", run.call_args_list[0].args)
        self.assertIn("http://172.20.0.3:8081/shop/900010", run.call_args_list[1].args)

    def test_bloom_probe_rejects_a_legal_shop_false_negative(self):
        with patch.object(cache, "run", return_value='{"success":false,"errorMsg":"店铺不存在"}'):
            with self.assertRaisesRegex(AssertionError, "Bloom filter or shop lookup rejected legal shop"):
                cache.probe_bloom_allows_shop(
                    "frontend-container", ["172.20.0.2"], 8081, 900010)

    def test_failure_report_persists_probe_error_and_container_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "cache-invalidation.json"
            rendered = cache.write_report(
                output, "npc-verify-cache-test", 990010, 9900101,
                {"login_code_route_ready": False}, False,
                "POST /user/code returned HTTP 502",
                {"backend": "connection refused", "frontend": "upstream unavailable"},
                "login_code_route")

            report = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(report["error"], "POST /user/code returned HTTP 502")
        self.assertEqual(report["diagnostics"]["backend"], "connection refused")
        self.assertEqual(report["stage"], "login_code_route")
        self.assertFalse(report["passed"])
        self.assertIn('"login_code_route_ready": false', rendered)

    def test_starting_a_run_removes_stale_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "cache-invalidation.json"
            output.write_text('{"passed": true}', encoding="utf-8")

            cache.clear_previous_report(output)

            self.assertFalse(output.exists())

    def test_stack_startup_failure_writes_current_run_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "cache-invalidation.json"
            with patch.object(cache.sys, "stderr", io.StringIO()) as stderr:
                cache.write_startup_failure(
                    output, "npc-verify-cache-test", 900010, 900011,
                    AssertionError("expected two backend replicas, found one"),
                    {"containers": "backend-1", "backend_logs": "startup error"})

            report = json.loads(output.read_text(encoding="utf-8"))

        self.assertFalse(report["passed"])
        self.assertEqual(report["stage"], "stack_startup")
        self.assertIn("found one", report["error"])
        self.assertEqual(report["diagnostics"]["backend_logs"], "startup error")
        self.assertIn('"stage": "stack_startup"', stderr.getvalue())

    def test_main_replaces_stale_success_when_backend_replica_count_is_wrong(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "cache-invalidation.json"
            output.write_text('{"passed": true}', encoding="utf-8")
            argv = ["cache-invalidation", "--project", "npc-verify-cache-test",
                    "--store-id", "900010", "--medicine-id", "900011", "--output", str(output)]
            with patch.object(cache.sys, "argv", argv), \
                    patch.object(cache, "container_ids", side_effect=[["frontend-1"], ["backend-1"]]), \
                    patch.object(cache, "stack_startup_diagnostics", return_value={"containers": "one backend"}), \
                    contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaisesRegex(AssertionError, "exactly one frontend and two backend replicas"):
                cache.main()

            report = json.loads(output.read_text(encoding="utf-8"))

        self.assertFalse(report["passed"])
        self.assertEqual(report["stage"], "stack_startup")
        self.assertIn("found frontend=1, backend=1", report["error"])

    def test_backend_probe_preserves_each_instance_http_result(self):
        responses = [
            type("Completed", (), {"returncode": 0, "stdout": '{"success":true}',
                                    "stderr": "HTTP/1.1 200 OK"})(),
            type("Completed", (), {"returncode": 8, "stdout": "",
                                    "stderr": "HTTP/1.1 502 Bad Gateway"})(),
        ]
        with patch.object(cache.subprocess, "run", side_effect=responses) as run:
            results = cache.probe_backend_login_code_routes(
                "frontend-container", ["172.20.0.2", "172.20.0.3"], 8081)

        self.assertEqual(results["172.20.0.2"]["exit_code"], 0)
        self.assertIn("200 OK", results["172.20.0.2"]["response_headers_or_transport"])
        self.assertEqual(results["172.20.0.3"]["exit_code"], 8)
        self.assertIn("502 Bad Gateway", results["172.20.0.3"]["response_headers_or_transport"])
        self.assertIn("http://172.20.0.3:8081/user/code?phone=19900000001",
                      run.call_args_list[1].args[0])

    def test_cache_stack_enables_test_login_code_exposure(self):
        override = (MODULE_PATH.parent / "cache-invalidation.compose.override.yml").read_text(
            encoding="utf-8")
        self.assertIn('APP_AUTH_EXPOSE_CODE: "true"', override)

    def test_cache_verifier_executes_bloom_probe_instead_of_marking_it_passed(self):
        source = MODULE_PATH.read_text(encoding="utf-8")
        self.assertIn('probe_bloom_allows_shop(frontend[0], ips, args.backend_port, 900010)', source)
        self.assertIn('checks["bloom_allows_legal_shop_on_both_replicas"] = True', source)
        self.assertNotIn('checks["legal_medicine_not_blocked"] = True', source)

    def test_read_path_probe_primes_only_first_replica_before_redis_change(self):
        redis_value = {"name": "authoritative"}
        local = {}
        calls = []

        def write(value):
            redis_value["name"] = value
            calls.append(("write", value))

        def fetch(replica):
            calls.append(("fetch", replica))
            if replica not in local:
                local[replica] = redis_value["name"]
            return [{"id": 900011, "name": local[replica]}]

        cache.verify_local_and_redis_hit(fetch, write, 900011, "probe-a", "probe-b")

        self.assertEqual(calls, [
            ("write", "probe-a"), ("fetch", "a"),
            ("write", "probe-b"), ("fetch", "a"), ("fetch", "b"),
        ])

    def test_read_path_probe_rejects_second_replica_serving_stale_local_value(self):
        redis_value = {"name": "authoritative"}
        local = {}

        def write(value):
            redis_value["name"] = value

        def fetch(replica):
            if replica not in local:
                local[replica] = redis_value["name"]
            return [{"id": 900011, "name": local[replica]}]

        # Simulate the old sequence's bug: both replicas cache A before Redis
        # changes. The helper must never accept replica B's stale Caffeine value.
        fetch("b")
        with self.assertRaisesRegex(AssertionError, "replica B \\(Redis hit\\).*expected 'probe-b'"):
            cache.verify_local_and_redis_hit(fetch, write, 900011, "probe-a", "probe-b")


if __name__ == "__main__":
    unittest.main()
