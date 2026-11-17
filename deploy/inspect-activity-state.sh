#!/bin/sh
set -eu
activity_id=${1:-}
case "$activity_id" in ''|*[!0-9]*) echo 'usage: inspect-activity-state.sh activity_id' >&2; exit 2;; esac
mysql_exec() {
  docker exec -i neighbor-pharmacy-test-mysql-1 sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot "$@"' sh "$@"
}
mysql_exec -N -B hmdp -e "SELECT id,status,start_at,end_at,NOW() AS mysql_now FROM seckill_activity WHERE id=${activity_id}"
docker exec neighbor-pharmacy-test-redis-1 redis-cli --raw HMGET "pharmacy:seckill:activity:${activity_id}" status startsAt endsAt perUserLimit
docker exec neighbor-pharmacy-test-redis-1 redis-cli --raw GET "pharmacy:seckill:status:${activity_id}"
docker exec neighbor-pharmacy-test-redis-1 redis-cli --raw GET "pharmacy:seckill:stock:${activity_id}"
