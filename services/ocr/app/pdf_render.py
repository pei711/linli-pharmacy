from __future__ import annotations

from pathlib import Path
from typing import Any

MAX_PAGE_PIXELS = 20_000_000
MAX_DOCUMENT_PIXELS = 40_000_000
RENDER_DPI = 150


class PdfRenderError(ValueError):
    pass


def render_scanned_pdf(content: bytes, directory: Path, fitz_module: Any | None = None) -> list[Path]:
    """Rasterize a validated, image-only PDF within fixed memory-oriented bounds."""
    if fitz_module is None:
        try:
            import fitz as fitz_module
        except ImportError as error:
            raise PdfRenderError("扫描版 PDF OCR 渲染组件未安装") from error
    try:
        document = fitz_module.open(stream=content, filetype="pdf")
        try:
            if document.needs_pass:
                raise PdfRenderError("暂不支持加密 PDF")
            if document.page_count < 1 or document.page_count > 10:
                raise PdfRenderError("PDF 页数必须在 1 到 10 页之间")
            paths: list[Path] = []
            total_pixels = 0
            for index, page in enumerate(document):
                rect = page.rect
                area_at_scale_one = float(rect.width) * float(rect.height)
                if area_at_scale_one <= 0:
                    raise PdfRenderError("PDF 页面尺寸无效")
                scale = min(RENDER_DPI / 72.0, (MAX_PAGE_PIXELS / area_at_scale_one) ** 0.5)
                estimated_pixels = int(area_at_scale_one * scale * scale)
                if total_pixels + estimated_pixels > MAX_DOCUMENT_PIXELS:
                    raise PdfRenderError("PDF 渲染总分辨率超过安全上限")
                pixmap = page.get_pixmap(matrix=fitz_module.Matrix(scale, scale), alpha=False)
                pixels = pixmap.width * pixmap.height
                if pixels < 1 or pixels > MAX_PAGE_PIXELS or total_pixels + pixels > MAX_DOCUMENT_PIXELS:
                    raise PdfRenderError("PDF 渲染分辨率超过安全上限")
                path = directory / f"page-{index + 1}.png"
                pixmap.save(str(path))
                paths.append(path)
                total_pixels += pixels
            return paths
        finally:
            document.close()
    except PdfRenderError:
        raise
    except Exception as error:
        raise PdfRenderError("扫描版 PDF 渲染失败") from error
