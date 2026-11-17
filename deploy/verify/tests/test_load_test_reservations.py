import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[2] / "load-test-reservations.py"
SPEC = importlib.util.spec_from_file_location("load_test_reservations", MODULE_PATH)
load_test = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load_test)


class ReservationLoadTestTest(unittest.TestCase):
    def test_percentile_uses_nearest_rank(self):
        self.assertEqual(load_test.percentile([9, 1, 5, 3, 7], 0.50), 5)
        self.assertEqual(load_test.percentile([9, 1, 5, 3, 7], 0.95), 9)
        self.assertEqual(load_test.percentile([], 0.95), 0.0)

    def test_non_loopback_target_requires_explicit_opt_in(self):
        with self.assertRaisesRegex(ValueError, "non-loopback"):
            load_test.ensure_test_target("http://192.0.2.10:18080", False)
        load_test.ensure_test_target("http://192.0.2.10:18080", True)
        load_test.ensure_test_target("http://127.0.0.1:18080", False)

    def test_selected_tokens_must_be_unique_and_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "users.csv"
            path.write_text("token\none\ntwo\n", encoding="utf-8")
            self.assertEqual(len(load_test.load_users(path, 2)), 2)
            path.write_text("token\none\none\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "distinct"):
                load_test.load_users(path, 2)
            with self.assertRaisesRegex(ValueError, "required"):
                load_test.load_users(path, 3)


if __name__ == "__main__":
    unittest.main()
