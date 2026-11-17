#!/bin/sh
# End-to-end OCR proxy/review/receipt test against the mock OCR HTTP upstream; not an OCR accuracy test.
set -eu
container=neighbor-pharmacy-test-mysql-1
database=hmdp
base=http://127.0.0.1:18080
phone=19900000001
document_id=
token=
temp_image=/tmp/neighbor-pharmacy-synthetic-image.png
mysql_root() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}
cleanup() {
  if [ -z "$document_id" ]; then
    document_id=$(mysql_root -N "$database" -e "SELECT id FROM inbound_document WHERE source_filename='neighbor-pharmacy-synthetic-image.png' ORDER BY id DESC LIMIT 1" 2>/dev/null || true)
  fi
  if [ -n "$document_id" ]; then
    batch_id=$(mysql_root -N "$database" -e "SELECT COALESCE(inventory_batch_id,0) FROM inbound_document WHERE id=$document_id" 2>/dev/null || true)
    if [ -n "$batch_id" ] && [ "$batch_id" != 0 ]; then
      mysql_root "$database" -e "UPDATE inbound_document SET inventory_batch_id=NULL WHERE id=$document_id; DELETE FROM inbound_document_review WHERE document_id=$document_id; DELETE FROM stock_movement WHERE batch_id=$batch_id AND movement_type='INBOUND'; DELETE FROM inventory_batch WHERE id=$batch_id"
    fi
    mysql_root "$database" -e "DELETE FROM inbound_document WHERE id=$document_id" 2>/dev/null || true
  fi
  if [ -n "$token" ]; then curl --silent --output /dev/null --request POST --header "authorization: $token" "$base/user/logout" || true; fi
  rm -f "$temp_image"
}
trap cleanup EXIT
printf 'synthetic image bytes for the explicit mock OCR test double' > "$temp_image"
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
recognized=$(curl --fail --silent --show-error --header "authorization: $token" \
  --form "file=@$temp_image;type=image/png" \
  "$base/api/v1/admin/inbound-documents/recognize?storeId=900010")
upload_values=$(python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["success"] is True, r; d=r["data"]; print("ocr_candidate="+json.dumps(d.get("candidate"),ensure_ascii=False),file=sys.stderr); print("sku_candidates="+json.dumps(d.get("skuCandidates"),ensure_ascii=False),file=sys.stderr); assert d["status"]=="PENDING_REVIEW" and d["requiresManualReview"] is True, d; assert d["candidate"]["needs_human_review"] is True, d["candidate"]; m=d["skuCandidates"][0]; assert float(m["matchScore"])>=0.99, m; print(str(d["documentId"])+" "+str(m["medicineId"]))' <<EOF
$recognized
EOF
)
document_id=${upload_values%% *}
medicine_id=${upload_values##* }
batch_no=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["candidate"]["batch_no"])' <<EOF
$recognized
EOF
)
echo 'mock_ocr_proxy_and_pending_review=PASS; exact_sku_suggestion=PASS'
payload=$(printf '{"medicineId":%s,"batchNo":"%s","productionDate":"2026-11-01","expireDate":"2027-11-01","quantity":1,"correctionNote":"mock OCR workflow integration test"}' "$medicine_id" "$batch_no")
confirmed=$(curl --fail --silent --show-error --header "authorization: $token" --header 'Content-Type: application/json' \
  --data "$payload" "$base/api/v1/admin/inbound-documents/$document_id/confirm")
batch_id=$(python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["success"] is True, r; print(r["data"])' <<EOF
$confirmed
EOF
)
duplicate=$(curl --silent --show-error --header "authorization: $token" --header 'Content-Type: application/json' \
  --data "$payload" "$base/api/v1/admin/inbound-documents/$document_id/confirm")
python3 -c 'import json,sys; assert json.load(sys.stdin)["success"] is False' <<EOF
$duplicate
EOF
verified=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM inbound_document d JOIN inbound_document_review r ON r.document_id=d.id JOIN inventory_batch b ON b.id=d.inventory_batch_id JOIN stock_movement m ON m.batch_id=b.id AND m.movement_type='INBOUND' WHERE d.id=$document_id AND d.status='CONFIRMED' AND r.reviewer_id=(SELECT id FROM tb_user WHERE phone='$phone') AND b.id=$batch_id AND b.medicine_id=$medicine_id AND m.quantity=1")
[ "$verified" = 1 ]
echo 'human_confirmation_transaction=PASS; review_audit=PASS; INBOUND_movement=PASS; duplicate_confirmation_rejected=PASS'
