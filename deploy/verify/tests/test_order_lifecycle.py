import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "order-lifecycle.py"
SPEC = importlib.util.spec_from_file_location("order_lifecycle", MODULE_PATH)
lifecycle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lifecycle)


class OrderLifecycleAssertionsTest(unittest.TestCase):
    def test_failure_report_preserves_completed_results_and_failed_stage(self):
        completed = {"action": "cancel", "order_no": "ORDER-1", "status": "CANCELLED"}
        report = lifecycle.json.loads(lifecycle.render_report(
            "npc-verify-lifecycle", [completed], False,
            "end_activity:990010", "activity release movement quantity must match"))

        self.assertFalse(report["passed"])
        self.assertEqual(report["results"], [completed])
        self.assertEqual(report["failed_step"], "end_activity:990010")
        self.assertIn("movement quantity", report["error"])

    def test_cancel_checks_every_batch_and_release_movement_quantity(self):
        before = {"batches": [
            {"batch_id": 11, "allocated": 2, "on_hand": 20, "order_reserved": 2},
            {"batch_id": 12, "allocated": 1, "on_hand": 10, "order_reserved": 1},
        ]}
        after = {"batches": [
            {"batch_id": 12, "on_hand": 10, "order_reserved": 0},
            {"batch_id": 11, "on_hand": 20, "order_reserved": 0},
        ], "release_qty": 3}
        lifecycle.assert_order_transition(before, after, "cancel", 3)

    def test_rejects_missing_batch_even_when_other_rows_match(self):
        before = {"batches": [{"batch_id": 11, "allocated": 2, "on_hand": 20, "order_reserved": 2}]}
        after = {"batches": [], "release_qty": 2}
        with self.assertRaisesRegex(AssertionError, "rows changed"):
            lifecycle.assert_order_transition(before, after, "cancel", 2)

    def test_rejects_wrong_pickup_ledger_quantity(self):
        before = {"batches": [{"batch_id": 11, "allocated": 2, "on_hand": 20, "order_reserved": 2}]}
        after = {"batches": [{"batch_id": 11, "on_hand": 18, "order_reserved": 0}], "sale_out_qty": 1}
        with self.assertRaisesRegex(AssertionError, "movement quantity"):
            lifecycle.assert_order_transition(before, after, "pickup", 2)

    def test_ended_activity_checks_each_batch_and_ledger_total(self):
        before = {"batches": [
            {"batch_id": 11, "activity_remaining": 3, "activity_reserved": 8},
            {"batch_id": 12, "activity_remaining": 0, "activity_reserved": 4},
        ]}
        after = {"batches": [
            {"batch_id": 12, "activity_remaining": 0, "activity_reserved": 4},
            {"batch_id": 11, "activity_remaining": 0, "activity_reserved": 5},
        ], "release_movements": 1, "release_quantity": 3}
        self.assertEqual(lifecycle.assert_activity_release(before, after), 3)

    def test_rejects_activity_release_movement_drift(self):
        before = {"batches": [{"batch_id": 11, "activity_remaining": 3, "activity_reserved": 8}]}
        after = {"batches": [{"batch_id": 11, "activity_remaining": 0, "activity_reserved": 5}],
                 "release_movements": 1, "release_quantity": 2}
        with self.assertRaisesRegex(AssertionError, "movement quantity"):
            lifecycle.assert_activity_release(before, after)


class ExpiredInitializingDiagnosticsTest(unittest.TestCase):
    def test_timeout_emits_mysql_and_redis_recovery_state(self):
        source = (MODULE_PATH.parent / "recover-expired-initializing-reservation.sh").read_text(
            encoding="utf-8")
        for field in ("mysql_detail", "redis_user_marker", "redis_reservation_state",
                      "pending_zset_score", "stock_restored_exactly"):
            self.assertIn(f'"{field}"', source)
        self.assertIn("probe details emitted as JSON", source)


if __name__ == "__main__":
    unittest.main()
