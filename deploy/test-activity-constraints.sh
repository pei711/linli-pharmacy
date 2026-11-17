#!/bin/sh
# One-time additions to the schema-only npc_activity_migration_run5 MySQL test clone.
set -eu
container=neighbor-pharmacy-test-mysql-1
database=npc_activity_migration_run5

mysql_exec() {
  docker exec -i "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}

/opt/neighbor-pharmacy-cloud/deploy/memory-preflight.sh
mysql_exec "$database" < /opt/neighbor-pharmacy-cloud/V4__pharmacy_dead_letter.sql
mysql_exec "$database" < /opt/neighbor-pharmacy-cloud/V5__pharmacy_cancellation_state.sql

mysql_exec "$database" -e "INSERT INTO tb_shop(id,name,type_id,images,address,x,y,sold,comments,score) VALUES (9905,'migration-test',1,'','test',121,31,0,0,50); INSERT INTO pharmacy_medicine(store_id,name,specification,sale_category) VALUES (9905,'migration-test','1 unit','OTC'); SET @medicine=LAST_INSERT_ID(); INSERT INTO seckill_activity(store_id,medicine_id,title,total_qty,remaining_qty,per_user_limit,start_at,end_at,status) VALUES (9905,@medicine,'migration test',2,2,1,'2099-01-01 10:00:00','2099-01-02 10:00:00','PUBLISHED'); SET @activity=LAST_INSERT_ID(); INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,expires_at) VALUES ('00000000-0000-0000-0000-000000000001',@activity,9905,1,'2099-01-01 10:00:00');"
activity_id=$(mysql_exec -Nse "SELECT id FROM ${database}.seckill_activity WHERE store_id=9905")
if mysql_exec "$database" -e "INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,expires_at) VALUES ('00000000-0000-0000-0000-000000000002',${activity_id},9905,1,'2099-01-01 10:00:00')" >/dev/null 2>&1; then
  echo 'FAIL: active user limit accepted a duplicate'; exit 1
fi
mysql_exec "$database" -e "UPDATE seckill_request SET status='FAILED' WHERE request_id='00000000-0000-0000-0000-000000000001'; INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,expires_at,status) VALUES ('00000000-0000-0000-0000-000000000003',${activity_id},9905,1,'2099-01-01 10:00:00','CANCELLED')"
/opt/neighbor-pharmacy-cloud/deploy/memory-preflight.sh
echo 'PASS: DLT/cancellation migrations applied; active user is unique and CANCELLED/failure states allow a future request'
