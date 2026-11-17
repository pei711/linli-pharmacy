#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
sample=${1:?Usage: ocr-image-selfcheck.sh /path/to/supplier-document.jpg-or.pdf}
[[ -f "$sample" ]] || { echo "Sample not found: $sample" >&2; exit 2; }
extension=${sample##*.}
case "${extension,,}" in
  jpg|jpeg|png|webp) expected_mode="image_ocr" ;;
  pdf) expected_mode="scanned_pdf_ocr" ;;
  *) echo "Expected a JPEG, PNG, WebP, or scanned PDF sample" >&2; exit 2 ;;
esac
port=${OCR_VERIFY_PORT:-18090}
name="npc-ocr-verify-$$"
image="neighbor-pharmacy-ocr-verify:local"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker build -t "$image" -f "$repo_root/services/ocr/Dockerfile" "$repo_root/services/ocr"
docker run -d --name "$name" -p "127.0.0.1:$port:8090" "$image" >/dev/null
base="http://127.0.0.1:$port"
for attempt in $(seq 1 120); do
  if curl --silent --fail "$base/health" >/dev/null; then break; fi
  sleep 2
done
curl --silent --show-error --fail "$base/health"
curl --silent --show-error --fail -F "file=@$sample" "$base/v1/inbound-documents/recognize" \
  -o /tmp/npc-ocr-selfcheck.json
python3 - "$expected_mode" /tmp/npc-ocr-selfcheck.json <<'PY'
import json, sys
expected_mode, path = sys.argv[1:]
with open(path, encoding="utf-8") as source:
    payload = json.load(source)
assert payload["input_mode"] == expected_mode, payload.get("input_mode")
assert payload["requires_manual_review"] is True
assert isinstance(payload.get("lines"), list) and payload["lines"], "OCR returned no text lines"
assert 0 <= payload["candidate"].get("confidence", -1) <= 1
print(f"PASS: PaddleOCR returned {expected_mode} text lines and a manually reviewable candidate")
PY
