#!/bin/sh
# Authenticated confirmation path using a synthetic pending OCR draft in the isolated test schema.
set -eu
container=neighbor-pharmacy-test-mysql-1
database=hmdp
base=http://127.0.0.1:18080
phone=19900000001
document_id=
mysql_root() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}
cleanup() {
  if [ -n "$document_id" ]; then
    batch_id=$(mysql_root -N "$database" -e "SELECT COALESCE(inventory_batch_id,0) FROM inbound_document WHERE id=$document_id" 2>/dev/null || true)
    if [ -n "$batch_id" ] && [ "$batch_id" != 0 ]; then
      mysql_root "$database" -e "UPDATE inbound_document SET inventory_batch_id=NULL WHERE id=$document_id"
      mysql_root "$database" -e "DELETE FROM inbound_document_review WHERE document_id=$document_id; DELETE FROM stock_movement WHERE batch_id=$batch_id AND movement_type='INBOUND'; DELETE FROM inventory_batch WHERE id=$batch_id"
    fi
    mysql_root "$database" -e "DELETE FROM inbound_document WHERE id=$document_id" 2>/dev/null || true
  fi
  if [ -n "${token:-}" ]; then curl --silent --output /dev/null --request POST --header "authorization: $token" "$base/user/logout" || true; fi
}
trap cleanup EXIT
user_id=$(mysql_root -N "$database" -e "SELECT id FROM tb_user WHERE phone='$phone' LIMIT 1")
medicine_id=$(mysql_root -N "$database" -e "SELECT id FROM pharmacy_medicine WHERE store_id=900010 AND enabled=TRUE ORDER BY id LIMIT 1")
[ -n "$user_id" ] && [ -n "$medicine_id" ] || { echo 'synthetic_owner_or_medicine_missing' >&2; exit 2; }
timestamp=$(date +%s)
batch_no="OCR-CONFIRM-$timestamp"
document_id=$(mysql_root -N "$database" -e "INSERT INTO inbound_document (store_id,uploaded_by,source_filename,content_type,ocr_text,candidate_json,status) VALUES (900010,$user_id,'synthetic-supplier-test.png','image/png','Synthetic OCR test draft',JSON_OBJECT('medicine_name','synthetic candidate','batch_no','$batch_no','production_date','2026-11-01','expire_date','2027-11-01','quantity',1),'PENDING_REVIEW'); SELECT LAST_INSERT_ID()")
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
payload=$(printf '{"medicineId":%s,"batchNo":"%s","productionDate":"2026-11-01","expireDate":"2027-11-01","quantity":1,"correctionNote":"synthetic integration check"}' "$medicine_id" "$batch_no")
response=$(curl --fail --silent --show-error --header "authorization: $token" --header 'Content-Type: application/json' --data "$payload" "$base/api/v1/admin/inbound-documents/$document_id/confirm")
batch_id=$(python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["success"] is True, r; print(r["data"])' <<EOF
$response
EOF
)
confirmed=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM inbound_document d JOIN inbound_document_review r ON r.document_id=d.id JOIN inventory_batch b ON b.id=d.inventory_batch_id JOIN stock_movement m ON m.batch_id=b.id AND m.movement_type='INBOUND' WHERE d.id=$document_id AND d.status='CONFIRMED' AND r.reviewer_id=$user_id AND b.id=$batch_id AND m.quantity=1")
[ "$confirmed" = 1 ]
echo 'authenticated_inbound_confirmation=PASS; review_audit=PASS; inventory_batch_and_INBOUND_movement=PASS'
