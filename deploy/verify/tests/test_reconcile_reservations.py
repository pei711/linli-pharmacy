import importlib.util
import contextlib
import io
import json
import re
import sys
import tempfile
from unittest.mock import patch
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "reconcile-reservations.py"
SPEC = importlib.util.spec_from_file_location("reconcile_reservations", MODULE_PATH)
reconcile = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reconcile)


class ReservationReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.report = {
            "success_orders": 100,
            "success_requests": 100,
            "failed_requests": 900,
            "total_requests": 1000,
            "failure_code_counts": {"SOLD_OUT": 900},
            "request_status_counts": {"SUCCESS": 100, "FAILED": 900},
            "duplicate_success_users": 0,
            "pending_requests": 0,
            "bad_inventory_batches": 0,
            "expired_order_allocations": 0,
            "expired_activity_batches": 0,
            "non_fefo_orders": 0,
            "activity_batch_mismatch": 0,
        }
        self.activity = {"total_qty": 100, "remaining_qty": 0}

    def test_all_load_test_reconciliation_assertions_pass(self):
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertTrue(all(checks.values()), checks)

    def test_rejects_wrong_stock_size_expired_batches_and_redis_drift(self):
        self.activity["total_qty"] = 101
        self.report["expired_activity_batches"] = 1
        checks = reconcile.build_assertions(self.report, self.activity, False, 100, 100, 1000)
        self.assertFalse(checks["activity_stock_matches_load_test"])
        self.assertFalse(checks["expired_batches_not_available_to_activity"])
        self.assertFalse(checks["redis_matches_mysql"])

    def test_rejects_load_failures_not_explained_by_sold_out(self):
        self.report["failure_code_counts"] = {"SOLD_OUT": 899, "INVALID_OR_INCONSISTENT": 1}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["failed_requests_are_explained_by_sold_out"])

    def test_rejects_missing_failed_request_error_codes(self):
        self.report["failure_code_counts"] = {}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["failed_error_codes_are_present"])
        self.assertFalse(checks["failed_error_code_distribution_is_complete"])

    def test_rejects_unspecified_failed_request_error_codes(self):
        self.report["failure_code_counts"] = {"SOLD_OUT": 899, "UNSPECIFIED": 1}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["failed_error_codes_are_present"])
        self.assertFalse(checks["failed_requests_are_explained_by_sold_out"])
        self.assertTrue(checks["failed_error_code_distribution_is_complete"])

    def test_rejects_empty_or_nonpositive_failed_error_code_buckets(self):
        self.report["failure_code_counts"] = {"SOLD_OUT": 900, " ": 0}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["failed_error_codes_are_present"])

    def test_rejects_success_request_and_order_count_mismatch(self):
        self.report["success_requests"] = 99
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["success_requests_match_orders"])

    def test_rejects_load_report_with_missing_requests(self):
        self.report["request_status_counts"] = {"SUCCESS": 100, "FAILED": 899}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["request_count_matches_load_size"])

    def test_rejects_unexpected_nonterminal_status(self):
        self.report["request_status_counts"] = {"SUCCESS": 100, "FAILED": 899, "RETRYING": 1}
        self.report["total_requests"] = 1000
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["all_requests_are_terminal"])

    def test_rejects_expired_requests_even_though_they_are_terminal(self):
        self.report["request_status_counts"] = {"SUCCESS": 100, "FAILED": 899, "EXPIRED": 1}
        self.report["failed_requests"] = 899
        self.report["failure_code_counts"] = {"SOLD_OUT": 899}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertTrue(checks["all_requests_are_terminal"])
        self.assertFalse(checks["no_expired_requests"])

    def test_failed_code_distribution_uses_database_failed_status_count(self):
        self.report["request_status_counts"] = {"SUCCESS": 100, "FAILED": 899, "EXPIRED": 1}
        self.report["failed_requests"] = 900
        self.report["failure_code_counts"] = {"SOLD_OUT": 900}
        checks = reconcile.build_assertions(self.report, self.activity, True, 100, 100, 1000)
        self.assertFalse(checks["failed_status_matches_failed_count"])
        self.assertFalse(checks["failed_error_code_distribution_is_complete"])

    def test_mysql_query_selects_hmdp_database(self):
        completed = type("Completed", (), {"stdout": '{"pending_requests":0}\n'})()
        with patch.object(reconcile.subprocess, "run", return_value=completed) as run:
            actual = reconcile.sql_json("npc-load-test", "SELECT JSON_OBJECT('pending_requests', 0)")

        command = run.call_args.args[0]
        shell_program = command[command.index("-ec") + 1]
        self.assertIn("mysql --default-character-set=utf8mb4 -uroot --batch --skip-column-names hmdp -e", shell_program)
        self.assertEqual(actual, {"pending_requests": 0})

    def test_mysql_errors_keep_database_diagnostic(self):
        error = reconcile.subprocess.CalledProcessError(
            1, ["mysql"], stderr="ERROR 1046 (3D000): No database selected")
        with patch.object(reconcile.subprocess, "run", side_effect=error):
            with self.assertRaisesRegex(RuntimeError, "database hmdp.*ERROR 1046.*No database selected"):
                reconcile.sql_json("npc-load-test", "SELECT 1")

    def test_battery_driver_preflight_checks_every_mysql_execution_path(self):
        verify_script = (MODULE_PATH.parent / "prepare-battery-driver.sh").read_text(encoding="utf-8")

        self.assertIn('re.search(r"\\bmysql\\s+(?:--|-)", line)', verify_script)
        self.assertIn('len(mysql_invocations) != 2', verify_script)
        self.assertIn('{"sql", "import_seed"}', verify_script)
        self.assertIn('"sql", "--skip-column-names hmdp -e \\"$1\\""', verify_script)
        self.assertIn('"import_seed", "mysql --default-character-set=utf8mb4 -uroot hmdp"', verify_script)
        self.assertIn('"cache startup is guarded by cleanup": ("p_cache", "try:\\n        ensure_kafka_volume(project)")', verify_script)
        self.assertIn('"cache seed precedes Bloom snapshot": (', verify_script)
        self.assertIn('"cache boot avoids ingress probes before backend readiness": (', verify_script)
        self.assertIn('"boot_stack", "if wait and not is_cache_stack:"', verify_script)
        self.assertIn('"cache startup failures collect logs": ("p_cache", \'for service in ("backend", "frontend", "mysql", "redis", "kafka")\')', verify_script)
        self.assertIn('"cache seed precedes Bloom snapshot": (', verify_script)
        self.assertIn('BloomFilterInitializer snapshots shops at backend startup', verify_script)
        patch_text = (MODULE_PATH.parent / "battery-driver-run4.patch").read_text(encoding="utf-8")
        self.assertIn('--skip-column-names hmdp -e "$1"', patch_text)
        self.assertIn('+        boot_stack(project, files)', patch_text)
        self.assertIn('for service in ("backend", "frontend", "mysql", "redis", "kafka")', patch_text)
        self.assertIn("if wait and not is_cache_stack:", patch_text)
        self.assertIn('ensure_kafka_volume(project)', patch_text)
        self.assertIn('compose(project, files, "up", "-d", "mysql", "redis", "kafka", "--wait", timeout=1200)', patch_text)
        self.assertIn('BloomFilterInitializer snapshots shops at backend startup', patch_text)
        self.assertIn('ensure_kafka_volume(project)', patch_text)

    def test_verification_mysql_clients_select_hmdp_before_query_or_seed_import(self):
        # Run 4 failed at SQL execution because mysql connected without a
        # default schema. Keep every verification entry point covered, not
        # only the reconciler and the external battery driver.
        verify_dir = MODULE_PATH.parent
        mysql_files = [path for path in verify_dir.glob("*")
                       if path.is_file() and path.suffix in {".py", ".sh"}
                       and path.name != "prepare-battery-driver.sh"]
        self.assertTrue(mysql_files, "expected verification MySQL CLI entry points")
        client_count = 0
        for path in mysql_files:
            source = path.read_text(encoding="utf-8")
            matches = list(re.finditer(r"\bmysql\s+(?=(?:--|-))", source))
            if not matches:
                continue
            client_count += len(matches)
            with self.subTest(script=path.name):
                # Check every CLI call independently, across Python string
                # continuations and shell line breaks. The schema must appear
                # before the query flag or seed-file redirection.
                for index, match in enumerate(matches):
                    next_start = matches[index + 1].start() if index + 1 < len(matches) else len(source)
                    fragment = source[match.start():min(next_start, match.start() + 400)]
                    query_positions = [position for token in ("-e", "<")
                                       if (position := fragment.find(token)) >= 0]
                    self.assertTrue(query_positions, f"mysql invocation has no SQL/input: {fragment!r}")
                    self.assertIn("hmdp", fragment[:min(query_positions)],
                                  f"mysql invocation must select hmdp: {fragment!r}")
        self.assertGreaterEqual(client_count, 10)

    def test_expired_initializing_driver_uses_activity_with_available_stock(self):
        patch_text = (MODULE_PATH.parent / "battery-driver-run4.patch").read_text(encoding="utf-8")
        prepare_script = (MODULE_PATH.parent / "prepare-battery-driver.sh").read_text(encoding="utf-8")

        self.assertIn("quantity=10, batch_qty=20, minutes=30", patch_text)
        self.assertIn("project, str(recovery_activity), fresh_uid", patch_text)
        self.assertIn('"expired-initializing uses a stocked recovery activity"', prepare_script)

    def test_battery_preparation_checks_workspace_fixes_before_patching_driver(self):
        verify_script = (MODULE_PATH.parent / "prepare-battery-driver.sh").read_text(encoding="utf-8")
        self.assertLess(verify_script.index("verify_workspace\n"), verify_script.index("verify_driver()"))
        for marker in (
            '"hmdp -e \\"$1\\""',
            '"anonymous POST /api/v1/activities/1/reserve"',
            '"SMOKE auth step 3/3"',
            '"documentation route must be public"',
            '"redis_hit_avoids_mysql"',
            '"bloom_allows_legal_shop_on_both_replicas"',
            '"clear_previous_report(args.output)"',
            '"stack_startup"',
            '"write_startup_failure"',
            '"capture_compose_logs("',
            '"DELETE FROM tb_shop_owner"',
            '"tenant_owner_token_selected"',
            'parser.add_argument("--store-b-token-env", required=True',
            '"AI evaluation gold"', '"没有销售记录"',
            'isPublicDocumentationPath(request.getRequestURI(), request.getContextPath())',
            'swagger_failure_diagnostics()', 'http://backend:8081$path',
        ):
            self.assertIn(marker, verify_script)

    def test_battery_preflight_validates_ops11_gold_groups_semantically(self):
        verify_script = (MODULE_PATH.parent / "prepare-battery-driver.sh").read_text(encoding="utf-8")

        self.assertIn('gold_path = root / "deploy/verify/ai-operations-gold.json"', verify_script)
        self.assertIn('ops11_groups = gold.get("OPS-11", {}).get("contains_any", [])', verify_script)
        self.assertIn('normalized_ops11_groups != required_ops11_groups', verify_script)

    def test_query_reports_failure_code_distribution_and_settlement_states(self):
        query = reconcile.reconciliation_query(123)
        self.assertIn("'failure_code_counts'", query)
        self.assertIn("GROUP BY COALESCE(failure_code, 'UNSPECIFIED')", query)
        self.assertIn("'failed_requests'", query)
        self.assertIn("'total_requests'", query)
        self.assertIn("status='FAILED'", query)
        self.assertIn("'request_status_counts'", query)
        self.assertIn("'pending_requests'", query)
        self.assertIn("WHERE activity_id=123", query)

    def test_settlement_query_is_compact_and_reports_failed_codes(self):
        query = reconcile.settlement_query(123)
        self.assertIn("'pending_requests'", query)
        self.assertIn("'request_status_counts'", query)
        self.assertIn("'failure_code_counts'", query)
        self.assertIn("WHERE activity_id=123", query)
        self.assertNotIn("order_batch_reservation", query)
        self.assertNotIn("inventory_batch", query)

    def test_main_polls_compact_state_then_runs_full_reconciliation_once(self):
        pending = {"pending_requests": 1, "request_status_counts": {"PROCESSING": 1},
                   "failed_requests": 0, "failure_code_counts": {}}
        settled = {"pending_requests": 0, "request_status_counts": {"SUCCESS": 1},
                   "failed_requests": 0, "failure_code_counts": {}}
        final = dict(self.report, success_orders=1, success_requests=1,
                     failed_requests=0, total_requests=1,
                     request_status_counts={"SUCCESS": 1}, pending_requests=0,
                     failure_code_counts={},
                     activity={"total_qty": 100, "remaining_qty": 0})
        argv = ["reconcile-reservations", "--project", "npc-load-test", "--activity-id", "123",
                "--expected-request-count", "1", "--settlement-timeout-seconds", "5",
                "--poll-interval-seconds", "0.001"]
        with patch.object(sys, "argv", argv), \
                patch.object(reconcile, "sql_json", side_effect=[pending, settled, final]) as query, \
                patch.object(reconcile, "redis_value", return_value="0"), \
                contextlib.redirect_stdout(io.StringIO()):
            result = reconcile.main()

        self.assertEqual(result, 0)
        self.assertEqual(query.call_count, 3)
        self.assertIn("pending_requests", query.call_args_list[0].args[1])
        self.assertNotIn("order_batch_reservation", query.call_args_list[0].args[1])
        self.assertIn("order_batch_reservation", query.call_args_list[2].args[1])

    def test_settlement_query_failure_writes_current_run_diagnostic_report(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "reconciliation.json"
            argv = ["reconcile-reservations", "--project", "npc-load-test",
                    "--activity-id", "123", "--output", str(output)]
            with patch.object(sys, "argv", argv), \
                    patch.object(reconcile, "sql_json",
                                 side_effect=RuntimeError("database hmdp: ERROR 1046 no database selected")), \
                    contextlib.redirect_stderr(io.StringIO()) as stderr:
                result = reconcile.main()

            report = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(result, 2)
        self.assertFalse(report["passed"])
        self.assertEqual(report["stage"], "settlement")
        self.assertEqual(report["activity_id"], 123)
        self.assertIn("ERROR 1046", report["error"])
        self.assertIn('"stage": "settlement"', stderr.getvalue())

    def test_query_rejects_any_expired_batch_linked_to_activity(self):
        query = reconcile.reconciliation_query(123)
        expired_activity_check = query.split("'expired_activity_batches',", 1)[1].split(
            "'non_fefo_orders'", 1
        )[0]
        self.assertIn("b.expire_date < CURRENT_DATE()", expired_activity_check)
        self.assertNotIn("ab.remaining_qty > 0", expired_activity_check)

    def test_timeout_refuses_pending_requests_and_prints_failure_codes(self):
        pending = dict(self.report, pending_requests=1,
                       request_status_counts={"SUCCESS": 100, "PROCESSING": 1},
                       failure_code_counts={"SOLD_OUT": 899})
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "timeout-report.json"
            argv = ["reconcile-reservations", "--project", "npc-load-test", "--activity-id", "123",
                    "--settlement-timeout-seconds", "1", "--poll-interval-seconds", "0.1",
                    "--output", str(output)]
            with patch.object(sys, "argv", argv), patch.object(reconcile, "sql_json", return_value=pending), \
                    patch.object(reconcile.time, "monotonic", side_effect=[0, 2]), \
                    contextlib.redirect_stdout(io.StringIO()) as stdout:
                result = reconcile.main()

            self.assertEqual(result, 2)
            self.assertIn("did not settle", stdout.getvalue())
            self.assertIn("PROCESSING", stdout.getvalue())
            self.assertIn("SOLD_OUT", stdout.getvalue())
            saved = json.loads(output.read_text(encoding="utf-8"))
            self.assertFalse(saved["passed"])
            self.assertEqual(saved["pending_requests"], 1)
            self.assertEqual(saved["failure_code_counts"], {"SOLD_OUT": 899})
            self.assertEqual(saved["settlement_timeout_seconds"], 1)

    def test_full_reconciliation_error_preserves_settled_failure_code_distribution(self):
        settled = {
            "pending_requests": 0,
            "total_requests": 1000,
            "request_status_counts": {"SUCCESS": 100, "FAILED": 900},
            "failed_requests": 900,
            "failure_code_counts": {"SOLD_OUT": 900},
        }
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "reconciliation-error.json"
            argv = ["reconcile-reservations", "--project", "npc-load-test", "--activity-id", "123",
                    "--output", str(output)]
            with patch.object(sys, "argv", argv), \
                    patch.object(reconcile, "sql_json", side_effect=[
                        settled, RuntimeError("MySQL query failed in database hmdp: ERROR 1046")
                    ]), contextlib.redirect_stdout(io.StringIO()) as stdout:
                result = reconcile.main()

            self.assertEqual(result, 2)
            report = json.loads(output.read_text(encoding="utf-8"))

        self.assertFalse(report["passed"])
        self.assertEqual(report["stage"], "full_reconciliation")
        self.assertIn("ERROR 1046", report["full_reconciliation_error"])
        self.assertEqual(report["failed_requests"], 900)
        self.assertEqual(report["failure_code_counts"], {"SOLD_OUT": 900})
        self.assertIn('"failure_code_counts": {\n    "SOLD_OUT": 900', stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
