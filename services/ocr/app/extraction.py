from __future__ import annotations

import re
import math
from dataclasses import dataclass
from datetime import date
from typing import Any

DATE_PATTERN = re.compile(r"(?<!\d)(20\d{2})[年./-]?(1[0-2]|0?[1-9])[月./-]?(3[01]|[12]\d|0?[1-9])日?(?!\d)")


@dataclass
class InboundCandidate:
    medicine_name: str | None = None
    specification: str | None = None
    batch_no: str | None = None
    production_date: str | None = None
    expire_date: str | None = None
    quantity: int | None = None
    purchase_unit_price: float | None = None
    confidence: float = 0.0
    missing_fields: list[str] | None = None
    needs_human_review: bool = True


def date_matches(text: str) -> list[str]:
    values: list[str] = []
    for match in DATE_PATTERN.finditer(text):
        year, month, day = (int(part) for part in match.groups())
        try:
            values.append(date(year, month, day).isoformat())
        except ValueError:
            continue
    return values


def normalize_confidence(value: Any) -> float:
    """Keep upstream OCR scores finite and within the documented 0..1 range."""
    if isinstance(value, bool):
        return 0.0
    try:
        score = float(value)
    except (TypeError, ValueError):
        return 0.0
    if not math.isfinite(score):
        return 0.0
    return min(1.0, max(0.0, score))


def extract_rules(lines: list[tuple[str, float]]) -> InboundCandidate:
    content = "\n".join(text for text, _ in lines)
    confidence = round(sum(normalize_confidence(score) for _, score in lines) / len(lines), 4) if lines else 0.0
    next_field = r"(?:药品名称|品名|商品名称|Medicine(?:\s+Name)?|Product\s+Name|规格|包装规格|SPECIFICATION|规格型号|批号|批次|LOT|BATCH|生产日期|生产|MFG|有效期至|有效期|失效日期|EXP|数量|件数|入库数|QUANTITY|QTY|采购单价|含税单价|单价|UNIT\s+PRICE|PRICE)"
    medicine = re.search(
        rf"(?:药品名称|品名|商品名称|Medicine(?:\s+Name)?|Product\s+Name)\s*[:：|]?\s*(.{{2,40}}?)(?=\s*{next_field}\s*[:：#|]?|[,，;；|\n]|$)",
        content,
        re.I,
    )
    batch = re.search(r"(?:批号|批次|LOT|BATCH)\s*[:：#|]?\s*([A-Z0-9][A-Z0-9_-]{2,30})", content, re.I)
    specification = re.search(
        rf"(?:包装规格|规格型号|规格|SPECIFICATION)\s*[:：|]?\s*(.{{1,40}}?)(?=\s*{next_field}\s*[:：#|]?|[,，;；|\n]|$)",
        content, re.I,
    )
    quantity = re.search(r"(?:数量|件数|入库数|QUANTITY|QTY)\s*[:：|]?\s*(\d{1,8})\s*(?:盒|瓶|袋|支|件|箱)?", content, re.I)
    production = re.search(r"(?:生产日期|生产|MFG)\s*[:：|]?\s*([^\n]{6,20})", content, re.I)
    expiry = re.search(r"(?:有效期至|有效期|失效日期|EXP)\s*[:：|]?\s*([^\n]{6,20})", content, re.I)
    unit_price = re.search(r"(?:采购单价|含税单价|单价|UNIT\s+PRICE|PRICE)\s*[:：|]?\s*(\d{1,8}(?:\.\d{1,2})?)", content, re.I)
    production_dates = date_matches(production.group(1)) if production else []
    expiry_dates = date_matches(expiry.group(1)) if expiry else []
    candidate = InboundCandidate(
        medicine_name=medicine.group(1).strip(" \t:：|,，;；") if medicine else None,
        specification=specification.group(1).strip(" \t:：|,，;；") if specification else None,
        batch_no=batch.group(1).strip() if batch else None,
        production_date=production_dates[0] if production_dates else None,
        expire_date=expiry_dates[0] if expiry_dates else None,
        quantity=int(quantity.group(1)) if quantity and int(quantity.group(1)) > 0 else None,
        purchase_unit_price=float(unit_price.group(1)) if unit_price else None,
        confidence=round(confidence, 4),
    )
    candidate.missing_fields = [
        name for name, value in (
            ("medicine_name", candidate.medicine_name),
            ("specification", candidate.specification),
            ("batch_no", candidate.batch_no),
            ("production_date", candidate.production_date),
            ("expire_date", candidate.expire_date),
            ("quantity", candidate.quantity),
            ("purchase_unit_price", candidate.purchase_unit_price),
        ) if value is None
    ]
    return candidate


def extract_table_rows(lines: list[tuple[str, float]]) -> list[InboundCandidate]:
    """Extract simple OCR table rows when a delimited header is available.

    PaddleOCR returns recognized text in reading order but not a stable table
    grid here. This deliberately supports explicit pipe/tab/comma tables only;
    unstructured layouts continue through the single-document rules path.
    """
    aliases = {
        "medicine_name": re.compile(r"药品名称|商品名称|品名|medicine(?:\s+name)?|product\s+name", re.I),
        "specification": re.compile(r"包装规格|规格型号|规格|specification", re.I),
        "batch_no": re.compile(r"批号|批次|lot|batch", re.I),
        "production_date": re.compile(r"生产日期|生产|mfg", re.I),
        "expire_date": re.compile(r"有效期至|有效期|失效日期|exp", re.I),
        "quantity": re.compile(r"数量|件数|入库数|quantity|qty", re.I),
        "purchase_unit_price": re.compile(r"采购单价|含税单价|单价|unit\s+price|price", re.I),
    }
    rows = [text.strip() for text, _ in lines if text.strip()]
    for index, header in enumerate(rows):
        delimiter = "|" if "|" in header else ("\t" if "\t" in header else ("," if "," in header else None))
        if delimiter is None:
            continue
        columns = [cell.strip().strip(" :：") for cell in header.strip("|").split(delimiter)]
        fields: list[str | None] = []
        for column in columns:
            fields.append(next((name for name, pattern in aliases.items() if pattern.search(column)), None))
        required_fields = {"medicine_name", "batch_no", "production_date", "expire_date", "quantity"}
        if not required_fields.issubset({field for field in fields if field}):
            continue

        candidates: list[InboundCandidate] = []
        for row in rows[index + 1:]:
            cells = [cell.strip() for cell in row.strip("|").split(delimiter)]
            if len(cells) != len(fields):
                if candidates:
                    break
                continue
            values = {field: cells[position] for position, field in enumerate(fields) if field}
            labeled = [
                (f"药品名称：{values['medicine_name']}", 0.0),
                (f"批号：{values['batch_no']}", 0.0),
                (f"生产日期：{values['production_date']}", 0.0),
                (f"有效期至：{values['expire_date']}", 0.0),
                (f"数量：{values['quantity']}", 0.0),
            ]
            if values.get("specification"):
                labeled.append((f"规格：{values['specification']}", 0.0))
            if values.get("purchase_unit_price"):
                labeled.append((f"采购单价：{values['purchase_unit_price']}", 0.0))
            candidate = extract_rules(labeled)
            required_values = (candidate.medicine_name, candidate.batch_no, candidate.production_date,
                               candidate.expire_date, candidate.quantity)
            if all(value is not None for value in required_values):
                candidate.confidence = round(
                    sum(normalize_confidence(score) for _, score in lines) / len(lines), 4
                ) if lines else 0.0
                candidates.append(candidate)
        if candidates:
            return candidates
    return []


def extract_candidates(lines: list[tuple[str, float]]) -> list[InboundCandidate]:
    """Return table rows when confidently structured, otherwise one draft."""
    table_candidates = extract_table_rows(lines)
    if table_candidates:
        return table_candidates
    return [extract_rules(lines)]


def valid_text(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    result = value.strip()
    return result[:limit] if result else None


def valid_date(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        return date.fromisoformat(value).isoformat()
    except ValueError:
        return None


def valid_quantity(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or not (1 <= value <= 100_000_000):
        return None
    return value


def valid_purchase_unit_price(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    try:
        price = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(price) or not (0 <= price <= 100_000_000):
        return None
    return round(price, 2)
