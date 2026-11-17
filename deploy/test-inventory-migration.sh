#!/bin/sh
# Run once against the dedicated server test MySQL container, never a production DB.
set -eu
container=neighbor-pharmacy-test-mysql-1
database=npc_inventory_test_20261116_v2
mysql_root() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}
mysql_root -e "CREATE DATABASE $database CHARACTER SET utf8mb4; CREATE TABLE $database.tb_shop (id BIGINT UNSIGNED PRIMARY KEY) ENGINE=InnoDB; INSERT INTO $database.tb_shop VALUES (1);"
mysql_root "$database" < pharmacy-migration-test.sql
mysql_root "$database" -e "INSERT INTO pharmacy_medicine(store_id,name,specification,sale_category) VALUES (1,'test medicine','test spec','OTC'); INSERT INTO inventory_batch(store_id,medicine_id,batch_no,production_date,expire_date,on_hand_qty,available_qty) VALUES (1,1,'batch-a','2026-01-01','2027-01-01',10,10);"
mysql_root "$database" -e "INSERT INTO tb_shop VALUES (2)"
if mysql_root "$database" -e "INSERT INTO inventory_batch(store_id,medicine_id,batch_no,production_date,expire_date) VALUES (2,1,'other-store','2026-01-01','2027-01-01')" >/dev/null 2>&1; then
  echo 'FAIL: cross-store batch accepted'; exit 1
fi
if mysql_root "$database" -e "UPDATE inventory_batch SET available_qty=-1 WHERE id=1" >/dev/null 2>&1; then
  echo 'FAIL: negative inventory accepted'; exit 1
fi
if mysql_root "$database" -e "UPDATE inventory_batch SET available_qty=5 WHERE id=1" >/dev/null 2>&1; then
  echo 'FAIL: inconsistent inventory accepted'; exit 1
fi
if mysql_root "$database" -e "INSERT INTO inventory_batch(store_id,medicine_id,batch_no,production_date,expire_date) VALUES (1,1,'batch-a','2026-01-01','2027-01-01')" >/dev/null 2>&1; then
  echo 'FAIL: duplicate batch accepted'; exit 1
fi
echo 'PASS: migration applied; cross-store, negative, unbalanced and duplicate batch rejected'
