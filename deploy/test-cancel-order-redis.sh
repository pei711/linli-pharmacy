#!/bin/sh
# Tests once-only cancellation projection using only reserved test keys.
set -eu
redis=neighbor-pharmacy-test-redis-1
script=/tmp/pharmacy-cancel-order-test.lua
prefix=pharmacy:seckill:test:cancel:20261116
stock="${prefix}:stock"
users="${prefix}:users"
reservation="${prefix}:reservation:req1"
pending="${prefix}:pending"

/opt/neighbor-pharmacy-cloud/deploy/memory-preflight.sh
docker cp /opt/neighbor-pharmacy-cloud/AI-dianping-backend/src/main/resources/pharmacy/cancel-order-redis.lua "$redis:$script"
cleanup() { docker exec "$redis" redis-cli DEL "$stock" "$users" "$reservation" "$pending" >/dev/null; }
trap cleanup EXIT
cleanup

docker exec "$redis" redis-cli SET "$stock" 5 >/dev/null
docker exec "$redis" redis-cli HSET "$users" 601 req1 >/dev/null
docker exec "$redis" redis-cli HSET "$reservation" state SUCCESS requestId req1 orderNo order-test >/dev/null
docker exec "$redis" redis-cli ZADD "$pending" 1900000000 req1 >/dev/null

apply_cancel() {
  restore="$1"; order="$2"
  docker exec "$redis" redis-cli --raw --eval "$script" "$stock" "$users" "$reservation" "$pending" , req1 "$order" "$restore" 601
}

test "$(apply_cancel 3 order-test)" = 1
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 8
test "$(docker exec "$redis" redis-cli --raw HGET "$users" 601)" = ''
test "$(apply_cancel 3 order-test)" = 1
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 8

echo 'PASS: committed cancellation restores eligible activity stock once and clears the old user marker'
