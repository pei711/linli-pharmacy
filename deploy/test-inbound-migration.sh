#!/bin/sh
# Apply the stock and OCR migrations to an empty disposable schema in the isolated test MySQL.
set -eu
cd /opt/neighbor-pharmacy-cloud
container=neighbor-pharmacy-test-mysql-1
database="npc_inbound_ddl_check_$(date +%s)"
mysql_root() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}
created=0
cleanup() {
  if [ "$created" = 1 ]; then mysql_root -e "DROP DATABASE $database"; fi
}
trap cleanup EXIT
mysql_root -e "CREATE DATABASE $database CHARACTER SET utf8mb4"
created=1
mysql_root "$database" -e "CREATE TABLE tb_shop (id BIGINT UNSIGNED PRIMARY KEY) ENGINE=InnoDB; CREATE TABLE tb_user (id BIGINT UNSIGNED PRIMARY KEY) ENGINE=InnoDB"
mysql_root "$database" < AI-dianping-backend/src/main/resources/db/migration/V1__pharmacy_inventory.sql
mysql_root "$database" < AI-dianping-backend/src/main/resources/db/migration/V6__pharmacy_inbound_documents.sql
mysql_root "$database" < AI-dianping-backend/src/main/resources/db/migration/V7__pharmacy_inbound_rejection_note.sql
mysql_root "$database" < AI-dianping-backend/src/main/resources/db/migration/V8__pharmacy_inbound_multiple_reviews.sql
mysql_root "$database" < AI-dianping-backend/src/main/resources/db/migration/V9__pharmacy_inbound_purchase_price.sql
tables=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND table_name IN ('inbound_document','inbound_document_review')")
[ "$tables" = 2 ]
column=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='inbound_document' AND column_name='review_note'")
[ "$column" = 1 ]
unique_index=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='inbound_document_review' AND index_name='uk_inbound_review_document'")
[ "$unique_index" = 0 ]
multi_index=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$database' AND table_name='inbound_document_review' AND index_name='idx_inbound_review_document'")
[ "$multi_index" = 1 ]
price_column=$(mysql_root -N "$database" -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$database' AND table_name='inbound_document_review' AND column_name='purchase_unit_price'")
[ "$price_column" = 1 ]
echo "PASS: inbound-document migrations applied; tables=$tables rejection_note=$column single_review_unique_index=$unique_index multi_review_index=$multi_index purchase_price_column=$price_column"
