import csv
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "ocr-evaluate.py"
SPEC = importlib.util.spec_from_file_location("ocr_evaluate", MODULE_PATH)
ocr_evaluate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ocr_evaluate)


class OcrEvaluateTest(unittest.TestCase):
    def test_reviewed_csv_uses_gold_rows_and_counts_actual_corrections(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = root / "sample.jpg"
            sample.write_bytes(b"image")
            gold_path = root / "gold.csv"
            gold_fields = ["sample_id", "path", *ocr_evaluate.FIELDS, "sku"]
            gold_rows = []
            for index in range(20):
                gold_rows.append({
                    "sample_id": f"sample-{index}", "path": "sample.jpg",
                    "medicine_name": "药品甲", "specification": "10mg×10片", "batch_no": f"B{index}",
                    "production_date": "2026-01-01", "expire_date": "2027-01-01",
                    "quantity": "10", "purchase_unit_price": "12.50", "sku": "SKU-1",
                })
            with gold_path.open("w", encoding="utf-8", newline="") as file:
                writer = csv.DictWriter(file, fieldnames=gold_fields)
                writer.writeheader()
                writer.writerows(gold_rows)

            catalog_path = root / "catalog.csv"
            with catalog_path.open("w", encoding="utf-8", newline="") as file:
                writer = csv.DictWriter(file, fieldnames=["sku", "medicine_name"])
                writer.writeheader()
                writer.writerow({"sku": "SKU-1", "medicine_name": "药品甲"})

            reviewed_path = root / "reviewed.csv"
            with reviewed_path.open("w", encoding="utf-8", newline="") as file:
                writer = csv.DictWriter(file, fieldnames=["sample_id", "field", "value"])
                writer.writeheader()
                writer.writerow({"sample_id": "sample-0", "field": "quantity", "value": "9"})

            output_path = root / "result.json"
            response = {
                "candidate": {
                    "medicine_name": "药品甲", "specification": "10mg × 10片", "batch_no": "B0",
                    "production_date": "2026-01-01", "expire_date": "2027-01-01",
                    "quantity": "10", "purchase_unit_price": 12.5,
                },
                "requires_manual_review": True,
                "input_mode": "image_ocr",
            }
            argv = ["ocr-evaluate", "--base-url", "http://127.0.0.1:18090",
                    "--gold-csv", str(gold_path), "--sku-catalog", str(catalog_path),
                    "--reviewed-csv", str(reviewed_path), "--output", str(output_path)]
            with patch.object(sys, "argv", argv), patch.object(ocr_evaluate, "upload", return_value=response):
                result = ocr_evaluate.main()

            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(result, 0)
            self.assertEqual(report["manual_correction"]["corrected_sample_count"], 1)
            self.assertEqual(report["manual_correction"]["sample_rate"], 0.05)
            self.assertEqual(report["manual_correction"]["corrected_field_count"], 1)
            self.assertEqual(report["manual_correction"]["field_rate"], 1 / 140)


if __name__ == "__main__":
    unittest.main()
