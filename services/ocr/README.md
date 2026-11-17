# Supplier inbound OCR service

This optional FastAPI service accepts JPEG, PNG, WebP supplier-document photos, or PDF files at `POST /v1/inbound-documents/recognize` (multipart field `file`). It returns text lines and candidate medicine name, specification, batch, dates, quantity, and purchase unit price. Candidate values are explicitly marked `requires_manual_review`; this service never changes inventory or writes to the pharmacy database.

The PaddleOCR model is loaded lazily on the first image or scanned-PDF request. Image uploads are limited to 10 MiB and 20 megapixels, validated against their declared image type, held in a temporary directory, and deleted after recognition. PDFs are limited to 10 MiB and 10 pages, and embedded text is capped at 100,000 characters. Text embedded in PDFs is parsed directly. Image-only PDFs are rendered with PyMuPDF at up to 150 DPI, capped at 20 megapixels per page and 40 megapixels per document, then sent through the same PaddleOCR image path. Encrypted and damaged PDFs are rejected. Embedded PDF text confidence is `0` because it has no OCR confidence score; scanned-PDF confidence comes from PaddleOCR. The response marks `input_mode` as `embedded_pdf_text`, `scanned_pdf_ocr`, or `image_ocr`. The response keeps the legacy `candidate` object and also returns `candidates`; explicit pipe-, tab-, or comma-delimited tables with recognizable medicine, batch, date, and quantity headers can produce multiple candidates. Specification and purchase unit price are parsed when present and remain unset when absent. This is a best-effort text-order parser, not a layout-aware table model: merged cells, multi-line cells, missing core headers, and visually aligned tables without delimiters are unsupported. All fields require human review.

LLM extraction is disabled by default. To enable it, set `OCR_LLM_EXTRACTION_ENABLED=true` and provide `AI_API_KEY`, `AI_BASE_URL`, and `AI_MODEL_NAME` through the runtime secret store. This sends OCR text from supplier documents to that model provider. The response is validated and remains a draft for human review; failures fall back to deterministic label-based extraction. Never put API keys in source control or the image.

Build and run locally with Docker from this directory. Bind the port to loopback while evaluating (`-p 127.0.0.1:8090:8090`) and configure a memory limit only after measuring model initialization and peak inference RSS with representative documents. The 2 GiB test server currently has no validated spare OCR budget, so this service is intentionally not part of its always-on Compose stack.

Run lightweight extraction tests from the repository root:

```sh
python -m unittest discover -s services/ocr/tests -v
```
