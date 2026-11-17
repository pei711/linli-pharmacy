#!/bin/sh
# Verify the isolated workbench degrades clearly when the optional OCR upstream is absent.
set -eu
container=neighbor-pharmacy-test-mysql-1
base=http://127.0.0.1:18080
phone=19900000001
temp_image=/tmp/neighbor-pharmacy-ocr-unavailable.png
token=
mysql_root() { docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"; }
cleanup() {
  if [ -n "$token" ]; then curl --silent --output /dev/null --request POST --header "authorization: $token" "$base/user/logout" || true; fi
  rm -f "$temp_image" /tmp/neighbor-pharmacy-ocr-unavailable-response.json
}
trap cleanup EXIT
before=$(mysql_root -N hmdp -e "SELECT COUNT(*) FROM inbound_document")
printf 'synthetic image bytes' > "$temp_image"
code_json=$(curl --fail --silent --show-error --request POST --data-urlencode "phone=$phone" "$base/user/code")
code=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["code"])' <<EOF
$code_json
EOF
)
login_json=$(curl --fail --silent --show-error --header 'Content-Type: application/json' --data "{\"phone\":\"$phone\",\"code\":\"$code\"}" "$base/user/login")
token=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"])' <<EOF
$login_json
EOF
)
status=$(curl --silent --show-error --output /tmp/neighbor-pharmacy-ocr-unavailable-response.json --write-out '%{http_code}' \
  --header "authorization: $token" --form "file=@$temp_image;type=image/png" \
  "$base/api/v1/admin/inbound-documents/recognize?storeId=900010")
[ "$status" = 503 ]
python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["success"] is False; assert "库存未变更" in r["errorMsg"]' /tmp/neighbor-pharmacy-ocr-unavailable-response.json
after=$(mysql_root -N hmdp -e "SELECT COUNT(*) FROM inbound_document")
[ "$before" = "$after" ]
echo 'ocr_missing_upstream=503_with_actionable_message; inventory_draft_count_unchanged=PASS'
