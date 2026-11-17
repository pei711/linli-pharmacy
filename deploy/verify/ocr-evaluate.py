#!/usr/bin/env python3
"""Run real OCR against a gold CSV and report field/SKU accuracy.

Gold CSV columns: sample_id,path,medicine_name,specification,batch_no,production_date,expire_date,quantity,purchase_unit_price,sku
SKU catalog CSV columns: sku,medicine_name. Paths are local image/PDF files. The OCR
API must be running with PaddleOCR enabled for image rows. An optional reviewed CSV
(sample_id,field,value) reports actual manual edits; without it, no manual-correction
rate is claimed.
"""
from __future__ import annotations

import argparse
import csv
import json
import mimetypes
import re
import statistics
import time
from decimal import Decimal, InvalidOperation
from collections import Counter
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

FIELDS = ("medicine_name", "specification", "batch_no", "production_date", "expire_date", "quantity",
          "purchase_unit_price")


def norm(value: object) -> str:
    return re.sub(r"\s+", "", str(value or "")).casefold()


def matches(field: str, actual: object, expected: object) -> bool:
    if field == "purchase_unit_price":
        try:
            return Decimal(str(actual)).quantize(Decimal("0.01")) == Decimal(str(expected)).quantize(Decimal("0.01"))
        except (InvalidOperation, TypeError, ValueError):
            return False
    return norm(actual) == norm(expected)


def upload(base: str, path: Path, timeout: float) -> dict:
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    boundary = "npc-ocr-eval-boundary"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{path.name}\"\r\n"
            f"Content-Type: {content_type}\r\n\r\n").encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = Request(base.rstrip("/") + "/v1/inbound-documents/recognize", data=body,
                      headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, method="POST")
    with urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--gold-csv", required=True, type=Path)
    parser.add_argument("--sku-catalog", required=True, type=Path)
    parser.add_argument("--reviewed-csv", type=Path, help="human corrections CSV: sample_id,field,value")
    parser.add_argument("--output", type=Path, default=Path("ocr-evaluation.json"))
    parser.add_argument("--timeout", type=float, default=60)
    args = parser.parse_args()
    with args.gold_csv.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    with args.sku_catalog.open(encoding="utf-8-sig", newline="") as f:
        sku_rows = list(csv.DictReader(f))
    required_gold = {"sample_id", "path", *FIELDS, "sku"}
    if not rows or not required_gold.issubset(rows[0]):
        raise SystemExit(f"Gold CSV must include columns: {', '.join(sorted(required_gold))}")
    if not sku_rows or not {"sku", "medicine_name"}.issubset(sku_rows[0]):
        raise SystemExit("SKU catalog must include sku and medicine_name columns")
    name_to_sku = {norm(row["medicine_name"]): row["sku"].strip() for row in sku_rows}
    if len(rows) != 20:
        raise SystemExit(f"Expected exactly 20 gold rows, found {len(rows)}")
    sample_ids = [row["sample_id"].strip() for row in rows]
    if any(not sample_id for sample_id in sample_ids) or len(set(sample_ids)) != len(sample_ids):
        raise SystemExit("Gold CSV sample_id values must be non-empty and unique")
    if any(not row["sku"].strip() for row in rows):
        raise SystemExit("Every gold row must include its expected SKU")
    gold_by_id = {row["sample_id"].strip(): row for row in rows}
    if len(name_to_sku) != len(sku_rows):
        raise SystemExit("SKU catalog contains duplicate normalized medicine names")
    catalog_skus = {row["sku"].strip() for row in sku_rows}
    if any(row["sku"].strip() not in catalog_skus for row in rows):
        raise SystemExit("Gold CSV contains an SKU absent from the SKU catalog")
    results, failures = [], []
    for row in rows:
        path = Path(row["path"]).expanduser()
        if not path.is_absolute():
            path = args.gold_csv.resolve().parent / path
        if not path.is_file():
            raise SystemExit(f"Missing sample file: {path}")
        started = time.perf_counter()
        try:
            response = upload(args.base_url, path, args.timeout)
            candidate = response.get("candidate", {})
            checks = {field: matches(field, candidate.get(field), row.get(field)) for field in FIELDS}
            matched_sku = name_to_sku.get(norm(candidate.get("medicine_name")))
            results.append({"sample_id": row["sample_id"], "checks": checks,
                            "candidate": {field: candidate.get(field) for field in FIELDS},
                            "sku_match": bool(row.get("sku")) and matched_sku == row.get("sku", "").strip(),
                            "matched_sku": matched_sku,
                            "requires_manual_review": response.get("requires_manual_review") is True,
                            "input_mode": response.get("input_mode"),
                            "latency_ms": round((time.perf_counter() - started) * 1000, 1)})
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as exc:
            failures.append({"sample_id": row["sample_id"], "error": type(exc).__name__})
    totals = Counter()
    for result in results:
        totals.update(result["checks"])
    counts = {field: sum(bool(result["checks"][field]) for result in results) for field in FIELDS}
    sku_count = sum(result["sku_match"] for result in results)
    input_modes = Counter(item.get("input_mode") for item in results)
    report = {
        "sample_count": len(rows), "processed_count": len(results), "failed_count": len(failures),
        # Failed uploads count as incorrect rather than disappearing from the denominator.
        "field_exact_match": {field: {"correct": counts[field], "total": len(rows),
                                      "accuracy": counts[field] / len(rows)} for field in FIELDS},
        "sku_name_match": {"correct": sku_count, "total": len(rows),
                           "accuracy": sku_count / len(rows)},
        "manual_correction": {
            "reviewed_sample_count": None,
            "corrected_sample_count": None,
            "sample_rate": None,
            "corrected_field_count": None,
            "field_rate": None,
            "note": "Not measured without --reviewed-csv; SKU match resolves OCR medicine name through --sku-catalog.",
        },
        "input_mode_counts": dict(input_modes),
        "requires_manual_review_all": bool(results) and all(item["requires_manual_review"] for item in results),
        "latency_ms_mean": round(statistics.fmean(item["latency_ms"] for item in results), 1) if results else None,
        "failures": failures, "results": results,
    }
    if args.reviewed_csv:
        with args.reviewed_csv.open(encoding="utf-8-sig", newline="") as f:
            reviewed_reader = csv.DictReader(f)
            edits = list(reviewed_reader)
            reviewed_fields = set(reviewed_reader.fieldnames or ())
        if not {"sample_id", "field", "value"}.issubset(reviewed_fields):
            raise SystemExit("Reviewed CSV must include columns: sample_id,field,value")
        seen_edits = set()
        result_by_id = {row["sample_id"]: row for row in results}
        for edit in edits:
            sample_id = (edit.get("sample_id") or "").strip()
            field = (edit.get("field") or "").strip()
            key = (sample_id, field)
            if key[0] not in gold_by_id or key[1] not in FIELDS or key in seen_edits:
                raise SystemExit(f"Invalid/duplicate reviewed correction: {key}")
            value = edit.get("value")
            if value is None or not value.strip():
                raise SystemExit(f"Reviewed correction value is empty: {key}")
            if key[0] not in result_by_id:
                raise SystemExit(f"Cannot validate correction for failed upload: {key}")
            candidate_value = result_by_id[key[0]]["candidate"].get(field)
            if matches(field, value, candidate_value):
                raise SystemExit(f"Correction value does not change the OCR candidate: {key}")
            seen_edits.add(key)
        corrected_samples = {sample_id for sample_id, _ in seen_edits}
        report["manual_correction"] = {
            "reviewed_sample_count": len(rows),
            "corrected_sample_count": len(corrected_samples),
            "sample_rate": len(corrected_samples) / len(rows),
            "corrected_field_count": len(seen_edits),
            "field_rate": len(seen_edits) / (len(rows) * len(FIELDS)),
            "note": f"Rates use all 20 gold samples and {len(rows) * len(FIELDS)} field slots; reviewed CSV rows identify fields manually changed.",
        }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not failures and report["requires_manual_review_all"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
