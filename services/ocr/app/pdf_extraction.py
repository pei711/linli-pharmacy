from __future__ import annotations

from io import BytesIO
from typing import Any

MAX_PDF_PAGES = 10
MAX_EXTRACTED_CHARS = 100_000


class PdfDocumentError(ValueError):
    def __init__(self, status_code: int, detail: str, *, scanned: bool = False):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail
        self.scanned = scanned


def extract_pdf_lines(content: bytes, reader_factory: Any | None = None) -> list[tuple[str, float]]:
    """Extract embedded text from small supplier PDFs; scanned PDFs need a renderer/OCR pipeline."""
    if reader_factory is None:
        try:
            from pypdf import PdfReader as reader_factory
        except ImportError as error:
            raise PdfDocumentError(503, "PDF 解析组件未安装") from error
    try:
        reader = reader_factory(BytesIO(content), strict=True)
        if reader.is_encrypted:
            raise PdfDocumentError(422, "暂不支持加密 PDF")
        if not reader.pages or len(reader.pages) > MAX_PDF_PAGES:
            raise PdfDocumentError(413, "PDF 页数必须在 1 到 10 页之间")
        lines: list[tuple[str, float]] = []
        char_count = 0
        for page in reader.pages:
            page_text = page.extract_text(extraction_mode="layout") or ""
            for line in page_text.splitlines():
                text = line.strip()
                if not text:
                    continue
                char_count += len(text)
                if char_count > MAX_EXTRACTED_CHARS:
                    raise PdfDocumentError(413, "PDF 提取文本不能超过 100,000 个字符")
                # Embedded PDF text has no OCR score; zero means unscored.
                lines.append((text, 0.0))
    except PdfDocumentError:
        raise
    except Exception as error:
        raise PdfDocumentError(422, "PDF 解析失败，请上传未加密且未损坏的文件") from error
    if not lines:
        raise PdfDocumentError(422, "PDF 未包含可提取文本", scanned=True)
    return lines
