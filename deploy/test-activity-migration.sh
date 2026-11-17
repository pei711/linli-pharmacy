#!/bin/sh
# Applies V2 to a fresh, schema-only clone inside the isolated pharmacy test stack.
set -eu

suffix="${1:-}"
case "$suffix" in
  ''|*[!a-zA-Z0-9_]* ) echo 'usage: test-activity-migration.sh safe_suffix' >&2; exit 2 ;;
esac

container=neighbor-pharmacy-test-mysql-1
database="npc_activity_migration_${suffix}"
migration=/opt/neighbor-pharmacy-cloud/V2__pharmacy_seckill_activity.sql
migration3=/opt/neighbor-pharmacy-cloud/V3__pharmacy_reservation_outbox.sql
migration4=/opt/neighbor-pharmacy-cloud/V4__pharmacy_dead_letter.sql
migration5=/opt/neighbor-pharmacy-cloud/V5__pharmacy_cancellation_state.sql

mysql_exec() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}

mysql_exec -e "CREATE DATABASE ${database} CHARACTER SET utf8mb4; GRANT ALL ON ${database}.* TO 'hmdp'@'%';"
docker exec "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --no-data --skip-add-drop-table --no-tablespaces hmdp' \
  | mysql_exec "$database"
mysql_exec "$database" < "$migration"
mysql_exec "$database" < "$migration3"
mysql_exec "$database" < "$migration4"
mysql_exec "$database" < "$migration5"

test "$(mysql_exec -Nse "SHOW TABLES FROM ${database} LIKE 'seckill_activity'")" = seckill_activity
test "$(mysql_exec -Nse "SHOW TABLES FROM ${database} LIKE 'seckill_activity_batch'")" = seckill_activity_batch
test "$(mysql_exec -Nse "SHOW TABLES FROM ${database} LIKE 'seckill_request'")" = seckill_request
test "$(mysql_exec -Nse "SHOW TABLES FROM ${database} LIKE 'outbox_event'")" = outbox_event
test "$(mysql_exec -Nse "SHOW TABLES FROM ${database} LIKE 'pharmacy_order'")" = pharmacy_order
test "$(mysql_exec -Nse "SHOW TABLES FROM ${database} LIKE 'dead_letter_event'")" = dead_letter_event
test "$(mysql_exec -Nse "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='${database}' AND table_name='inventory_batch' AND index_name='uk_batch_store_medicine_id'")" = 1

mysql_exec "$database" -e "INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (9901,'migration-test',1,'','test',121,31,0,0,50); INSERT INTO pharmacy_medicine(store_id,name,specification,sale_category) VALUES (9901,'migration-test','1 unit','OTC'); SET @medicine=LAST_INSERT_ID(); INSERT INTO seckill_activity(store_id,medicine_id,title,total_qty,remaining_qty,per_user_limit,start_at,end_at,status) VALUES (9901,@medicine,'migration test',2,2,1,'2099-01-01 10:00:00','2099-01-02 10:00:00','PUBLISHED'); SET @activity=LAST_INSERT_ID(); INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,expires_at) VALUES ('00000000-0000-0000-0000-000000000001',@activity,9901,1,'2099-01-01 10:00:00');"
activity_id=$(mysql_exec -Nse "SELECT id FROM ${database}.seckill_activity WHERE store_id=9901")
if mysql_exec "$database" -e "INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,expires_at) VALUES ('00000000-0000-0000-0000-000000000002',${activity_id},9901,1,'2099-01-01 10:00:00')" >/dev/null 2>&1; then
  echo 'FAIL: active user limit accepted a duplicate'; exit 1
fi
mysql_exec "$database" -e "UPDATE seckill_request SET status='FAILED' WHERE request_id='00000000-0000-0000-0000-000000000001'; INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,expires_at) VALUES ('00000000-0000-0000-0000-000000000003',${activity_id},9901,1,'2099-01-01 10:00:00')"

echo "PASS: V2-V5 activity, request, order, outbox, DLT schema and active-user constraint created in ${database}"
