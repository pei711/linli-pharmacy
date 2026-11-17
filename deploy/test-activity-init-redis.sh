#!/bin/sh
# Exercises the idempotent publication Lua script only under a reserved test-key prefix.
set -eu

redis=neighbor-pharmacy-test-redis-1
script=/tmp/activity-init-test.lua
prefix=pharmacy:seckill:test:init:20261116
stock="${prefix}:stock"
status="${prefix}:status"
meta="${prefix}:activity"

docker cp /opt/neighbor-pharmacy-cloud/AI-dianping-backend/src/main/resources/pharmacy/activity-init.lua "$redis:$script"
cleanup() { docker exec "$redis" redis-cli DEL "$stock" "$status" "$meta" >/dev/null; }
trap cleanup EXIT
cleanup

result=$(docker exec "$redis" redis-cli --raw --eval "$script" "$stock" "$status" "$meta" , 10 1800000000 1900000000 9001 1 120 1)
test "$result" = 1
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 10
test "$(docker exec "$redis" redis-cli --raw GET "$status")" = PUBLISHED

# A retry after Redis accepted the init but MySQL state update failed must preserve decremented stock.
docker exec "$redis" redis-cli DECRBY "$stock" 3 >/dev/null
result=$(docker exec "$redis" redis-cli --raw --eval "$script" "$stock" "$status" "$meta" , 10 1800000000 1900000000 9001 1 120 1)
test "$result" = 2
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 7

# Partial state and lost keys after a published activity must fail closed.
docker exec "$redis" redis-cli DEL "$meta" >/dev/null
result=$(docker exec "$redis" redis-cli --raw --eval "$script" "$stock" "$status" "$meta" , 10 1800000000 1900000000 9001 1 120 1)
test "$result" = -1
cleanup
result=$(docker exec "$redis" redis-cli --raw --eval "$script" "$stock" "$status" "$meta" , 10 1800000000 1900000000 9001 0 120 1)
test "$result" = -2

echo 'PASS: init is atomic/idempotent, never overwrites remaining stock, and fails closed on partial/lost state'
