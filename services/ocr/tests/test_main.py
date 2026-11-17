import unittest
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.extraction import date_matches, extract_candidates, extract_rules, normalize_confidence, valid_purchase_unit_price
from app.pdf_extraction import PdfDocumentError, extract_pdf_lines
from app.pdf_render import MAX_DOCUMENT_PIXELS, MAX_PAGE_PIXELS, PdfRenderError, render_scanned_pdf


class OcrExtractionTests(unittest.TestCase):
    def test_twenty_reproducible_synthetic_supplier_documents(self):
        # These cover rule extraction only; they do not measure image OCR accuracy.
        samples = [
            ("药品名称：阿莫西林胶囊\n批号：AMX202601\n生产日期：2026-01-02\n有效期至：2028-01-02\n数量：12盒", "阿莫西林胶囊", "AMX202601", "2026-01-02", "2028-01-02", 12),
            ("品名:布洛芬片\n批次:IBU_26A\n生产日期:2026/02/03\n失效日期:2028/02/03\n件数:24", "布洛芬片", "IBU_26A", "2026-02-03", "2028-02-03", 24),
            ("商品名称：维生素C片\nLOT:VTC-2603\n生产 2026年3月4日\n有效期 2028年3月4日\n入库数 36瓶", "维生素C片", "VTC-2603", "2026-03-04", "2028-03-04", 36),
            ("药品名称：氯雷他定片\n批号#LOR2604\nMFG:2026-04-05\nEXP:2028-04-05\n数量:8盒", "氯雷他定片", "LOR2604", "2026-04-05", "2028-04-05", 8),
            ("品名：口罩\n批号：MASK_2605\n生产日期：2026.05.06\n有效期至：2028.05.06\n数量：100件", "口罩", "MASK_2605", "2026-05-06", "2028-05-06", 100),
            ("药品名称：碘伏\n批次：IOD2606\n生产日期：2026年6月7日\n有效期至：2028年6月7日\n数量：18瓶", "碘伏", "IOD2606", "2026-06-07", "2028-06-07", 18),
            ("商品名称：创可贴\nBATCH:PLS2607\n生产日期：2026-07-08\n失效日期：2028-07-08\n件数：50盒", "创可贴", "PLS2607", "2026-07-08", "2028-07-08", 50),
            ("品名：口服补液盐\n批号：ORS2608\nMFG：2026/08/09\nEXP：2028/08/09\n入库数：15盒", "口服补液盐", "ORS2608", "2026-08-09", "2028-08-09", 15),
            ("药品名称：生理盐水\n批次：SAL2609\n生产：2026-09-10\n有效期：2028-09-10\n数量：20瓶", "生理盐水", "SAL2609", "2026-09-10", "2028-09-10", 20),
            ("商品名称：体温计\nLOT:TEM2610\n生产日期：2026年10月11日\n有效期至：2028年10月11日\n数量：6支", "体温计", "TEM2610", "2026-10-11", "2028-10-11", 6),
            ("品名：酒精棉片\n批号：ALC2611\n生产日期：2026-11-12\n有效期至：2028-11-12\n件数：30袋", "酒精棉片", "ALC2611", "2026-11-12", "2028-11-12", 30),
            ("药品名称：蒙脱石散\n批次：DIO2612\nMFG:2026/12/13\nEXP:2028/12/13\n数量:10盒", "蒙脱石散", "DIO2612", "2026-12-13", "2028-12-13", 10),
            ("商品名称：医用纱布\n批号：GAU2701\n生产日期：2027-01-14\n有效期：2029-01-14\n入库数：40包", "医用纱布", "GAU2701", "2027-01-14", "2029-01-14", 40),
            ("品名：口罩\nBATCH:MASK2702\n生产日期：2027年2月15日\n有效期至：2029年2月15日\n数量：200件", "口罩", "MASK2702", "2027-02-15", "2029-02-15", 200),
            ("药品名称：葡萄糖粉\n批号：GLU2703\n生产日期：2027/03/16\n失效日期：2029/03/16\n件数：9袋", "葡萄糖粉", "GLU2703", "2027-03-16", "2029-03-16", 9),
            ("商品名称：医用胶带\n批次：TAP2704\nMFG：2027-04-17\nEXP：2029-04-17\n数量：11卷", "医用胶带", "TAP2704", "2027-04-17", "2029-04-17", 11),
            ("品名：退热贴\n批号：COO2705\n生产：2027年5月18日\n有效期至：2029年5月18日\n数量：22片", "退热贴", "COO2705", "2027-05-18", "2029-05-18", 22),
            ("药品名称：人工泪液\nLOT:EY2706\n生产日期：2027-06-19\n有效期：2029-06-19\n数量：7支", "人工泪液", "EY2706", "2027-06-19", "2029-06-19", 7),
            ("商品名称：棉签\n批次：SWB2707\n生产日期：2027/07/20\n有效期至：2029/07/20\n入库数：60包", "棉签", "SWB2707", "2027-07-20", "2029-07-20", 60),
            ("品名：电子体温计\n批号：ET2708\nMFG:2027年8月21日\nEXP:2029年8月21日\n件数:5", "电子体温计", "ET2708", "2027-08-21", "2029-08-21", 5),
        ]
        for document, name, batch, production, expiry, quantity in samples:
            with self.subTest(name=name, batch=batch):
                candidate = extract_rules([(line, 0.95) for line in document.splitlines()])
                self.assertEqual(name, candidate.medicine_name)
                self.assertEqual(batch, candidate.batch_no)
                self.assertEqual(production, candidate.production_date)
                self.assertEqual(expiry, candidate.expire_date)
                self.assertEqual(quantity, candidate.quantity)
                self.assertTrue(candidate.needs_human_review)

    def test_extracts_only_labeled_fields_and_valid_dates(self):
        lines = [
            ("药品名称：维生素C片", 0.99),
            ("规格：100mg×60片", 0.99),
            ("批号：LOT-AB123", 0.98),
            ("生产日期：2026年01月03日", 0.97),
            ("有效期至：2027-12-31", 0.96),
            ("数量：120盒", 0.95),
            ("采购单价：12.50", 0.95),
        ]
        result = extract_rules(lines)
        self.assertEqual(result.medicine_name, "维生素C片")
        self.assertEqual(result.batch_no, "LOT-AB123")
        self.assertEqual(result.production_date, "2026-01-03")
        self.assertEqual(result.expire_date, "2027-12-31")
        self.assertEqual(result.quantity, 120)
        self.assertEqual(result.specification, "100mg×60片")
        self.assertEqual(result.purchase_unit_price, 12.5)
        self.assertEqual(result.missing_fields, [])
        self.assertTrue(result.needs_human_review)

    def test_extracts_specification_and_non_negative_purchase_price(self):
        result = extract_rules([("药品名称：维生素C片 规格：100mg×60片 单价：12.50", 0.9)])
        self.assertEqual(result.specification, "100mg×60片")
        self.assertEqual(result.purchase_unit_price, 12.5)
        self.assertEqual(valid_purchase_unit_price(-1), None)
        self.assertEqual(valid_purchase_unit_price(float("inf")), None)

    def test_extracts_optional_specification_and_price_table_columns(self):
        rows = extract_candidates([
            ("品名 | 规格 | 批号 | 生产日期 | 有效期 | 数量 | 采购单价", 0.9),
            ("维生素C片 | 100mg×60片 | VTC2601 | 2026-01-02 | 2028-01-02 | 10 | 12.50", 0.9),
        ])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].specification, "100mg×60片")
        self.assertEqual(rows[0].purchase_unit_price, 12.5)

    def test_extracts_fields_from_single_line_and_pipe_table_rows(self):
        samples = [
            "药品名称：阿莫西林胶囊 批号：AMX202601 生产日期：2026-01-02 有效期至：2028-01-02 数量：12盒",
            "品名 | 布洛芬片 | 批次 | IBU_26A | 生产 | 2026/02/03 | 有效期 | 2028/02/03 | 入库数 | 24",
        ]
        for text, expected_name, expected_batch, expected_production, expected_expiry, expected_qty in [
            (samples[0], "阿莫西林胶囊", "AMX202601", "2026-01-02", "2028-01-02", 12),
            (samples[1], "布洛芬片", "IBU_26A", "2026-02-03", "2028-02-03", 24),
        ]:
            with self.subTest(text=text):
                result = extract_rules([(text, 0.9)])
                self.assertEqual(result.medicine_name, expected_name)
                self.assertEqual(result.batch_no, expected_batch)
                self.assertEqual(result.production_date, expected_production)
                self.assertEqual(result.expire_date, expected_expiry)
                self.assertEqual(result.quantity, expected_qty)
                self.assertTrue(result.needs_human_review)

    def test_extracts_multiple_explicit_pipe_table_rows(self):
        lines = [
            ("品名 | 批号 | 生产日期 | 有效期 | 数量", 0.9),
            ("阿莫西林胶囊 | AMX2601 | 2026-01-02 | 2028-01-02 | 12", 0.91),
            ("布洛芬片 | IBU2602 | 2026-02-03 | 2028-02-03 | 24", 0.92),
        ]
        results = extract_candidates(lines)
        self.assertEqual(len(results), 2)
        self.assertEqual(results[0].medicine_name, "阿莫西林胶囊")
        self.assertEqual(results[0].batch_no, "AMX2601")
        self.assertEqual(results[0].quantity, 12)
        self.assertEqual(results[1].medicine_name, "布洛芬片")
        self.assertEqual(results[1].batch_no, "IBU2602")
        self.assertEqual(results[1].quantity, 24)
        self.assertTrue(all(result.needs_human_review for result in results))

    def test_extracts_delimited_rows_with_tabs_and_commas(self):
        header = ["品名", "批号", "生产日期", "有效期", "数量"]
        row = ["口罩", "MASK2601", "2026-01-02", "2028-01-02", "20"]
        for delimiter in ("\t", ","):
            with self.subTest(delimiter=repr(delimiter)):
                results = extract_candidates([
                    (delimiter.join(header), 0.9),
                    (delimiter.join(row), 0.9),
                ])
                self.assertEqual(len(results), 1)
                self.assertEqual(results[0].medicine_name, "口罩")
                self.assertEqual(results[0].batch_no, "MASK2601")
                self.assertEqual(results[0].quantity, 20)

    def test_unstructured_text_keeps_single_candidate_fallback(self):
        results = extract_candidates([("药品名称：口罩\n数量：10件", 0.8)])
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0].medicine_name, "口罩")
        self.assertEqual(results[0].quantity, 10)

    def test_missing_or_invalid_values_remain_unset(self):
        result = extract_rules([("2026-02-31 数量：0", 0.8)])
        self.assertIsNone(result.production_date)
        self.assertIsNone(result.expire_date)
        self.assertIsNone(result.quantity)
        self.assertGreater(len(result.missing_fields), 0)

    def test_date_parser_rejects_impossible_calendar_dates(self):
        self.assertEqual(date_matches("2026年02月31日 2026-02-28"), ["2026-02-28"])

    def test_confidence_is_finite_and_clamped_before_averaging(self):
        self.assertEqual(normalize_confidence(float("nan")), 0.0)
        self.assertEqual(normalize_confidence(float("inf")), 0.0)
        self.assertEqual(normalize_confidence(-0.2), 0.0)
        self.assertEqual(normalize_confidence(1.4), 1.0)
        candidate = extract_rules([("数量：2盒", 1.4), ("批号：ABC123", -0.2)])
        self.assertEqual(candidate.confidence, 0.5)

    def test_extracts_pdf_embedded_text_without_claiming_ocr_confidence(self):
        class Page:
            def extract_text(self, extraction_mode):
                self.assert_mode = extraction_mode
                return "Medicine Name: Ibuprofen\nBATCH: IBU2601\nMFG: 2026-01-02\nEXP: 2028-01-02\nQTY: 12"

        class Reader:
            is_encrypted = False
            pages = [Page()]

        lines = extract_pdf_lines(b"%PDF-1.7 synthetic", reader_factory=lambda *_args, **_kwargs: Reader())
        candidate = extract_rules(lines)
        self.assertEqual(candidate.medicine_name, "Ibuprofen")
        self.assertEqual(candidate.batch_no, "IBU2601")
        self.assertEqual(candidate.production_date, "2026-01-02")
        self.assertEqual(candidate.expire_date, "2028-01-02")
        self.assertEqual(candidate.quantity, 12)
        self.assertEqual(candidate.confidence, 0.0)
        self.assertTrue(all(score == 0.0 for _, score in lines))

    def test_pdf_extraction_rejects_scanned_or_oversized_documents(self):
        class Page:
            def extract_text(self, extraction_mode):
                return " "

        class Reader:
            is_encrypted = False
            pages = [Page()]

        with self.assertRaises(PdfDocumentError) as error:
            extract_pdf_lines(b"%PDF-1.7 synthetic", reader_factory=lambda *_args, **_kwargs: Reader())
        self.assertEqual(error.exception.status_code, 422)
        self.assertTrue(error.exception.scanned)

        class TooManyPages:
            is_encrypted = False
            pages = [Page()] * 11

        with self.assertRaises(PdfDocumentError) as error:
            extract_pdf_lines(b"%PDF-1.7 synthetic", reader_factory=lambda *_args, **_kwargs: TooManyPages())
        self.assertEqual(error.exception.status_code, 413)
        self.assertFalse(error.exception.scanned)

    def test_scanned_pdf_renderer_respects_page_and_document_pixel_budgets(self):
        class Pixmap:
            width = 1200
            height = 1600

            def save(self, path):
                Path(path).write_bytes(b"png-test")

        class Page:
            rect = type("Rect", (), {"width": 612, "height": 792})()

            def get_pixmap(self, matrix, alpha):
                self.matrix = matrix
                self.alpha = alpha
                return Pixmap()

        class Document:
            needs_pass = False
            page_count = 2

            def __iter__(self):
                return iter([Page(), Page()])

            def close(self):
                self.closed = True

        class FakeFitz:
            @staticmethod
            def open(**_kwargs):
                return Document()

            class Matrix:
                def __init__(self, x, y):
                    self.x, self.y = x, y

        with tempfile.TemporaryDirectory() as directory:
            paths = render_scanned_pdf(b"%PDF-test", Path(directory), FakeFitz)
            self.assertEqual(len(paths), 2)
            self.assertTrue(all(path.exists() for path in paths))
            self.assertLessEqual(2 * 1200 * 1600, MAX_DOCUMENT_PIXELS)
            self.assertLessEqual(1200 * 1600, MAX_PAGE_PIXELS)

    def test_scanned_pdf_renderer_rejects_excessive_page_count(self):
        class Document:
            needs_pass = False
            page_count = 11
            closed = False

            def close(self):
                self.closed = True

        document = Document()

        class FakeFitz:
            @staticmethod
            def open(**_kwargs):
                return document

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(PdfRenderError):
                render_scanned_pdf(b"%PDF-test", Path(directory), FakeFitz)
        self.assertTrue(document.closed)


if __name__ == "__main__":
    unittest.main()
