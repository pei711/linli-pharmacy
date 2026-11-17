#!/bin/sh
# Runs the actual Lua admission script against test-only keys in the isolated Redis container.
set -eu

redis=neighbor-pharmacy-test-redis-1
script=/tmp/pharmacy-reserve-test.lua
prefix=pharmacy:seckill:test:reserve:20261116
stock="${prefix}:stock"
status="${prefix}:status"
meta="${prefix}:activity"
users="${prefix}:users"
pending="${prefix}:pending"

docker cp /opt/neighbor-pharmacy-cloud/AI-dianping-backend/src/main/resources/pharmacy/reserve.lua "$redis:$script"
cleanup() {
  docker exec "$redis" redis-cli DEL "$stock" "$status" "$meta" "$users" "$pending" \
    "${prefix}:reservation:req1" "${prefix}:reservation:req2" "${prefix}:reservation:req3" \
    "${prefix}:reservation:req4" "${prefix}:reservation:req5" "${prefix}:activity-rate" \
    "${prefix}:ip-rate" "${prefix}:user-rate" >/dev/null
}
trap cleanup EXIT
cleanup

docker exec "$redis" redis-cli SET "$stock" 2 >/dev/null
docker exec "$redis" redis-cli SET "$status" PUBLISHED >/dev/null
docker exec "$redis" redis-cli HSET "$meta" startsAt 1800000000 endsAt 1900000000 perUserLimit 1 >/dev/null

eval_reserve() {
  request_id="$1"; user_id="$2"; now="$3"; activity_limit="$4"
  docker exec "$redis" redis-cli --raw --eval "$script" "$stock" "$status" "$meta" "$users" \
    "${prefix}:reservation:${request_id}" "$pending" "${prefix}:activity-rate" "${prefix}:ip-rate" \
    "${prefix}:user-rate" , "$request_id" "$user_id" 1 "$now" "$((now + 120))" 9001 2 \
    "$activity_limit" 10 5 604800
}

test "$(eval_reserve req1 101 1850000000 100)" = 0
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 1
test "$(eval_reserve req1 101 1850000000 100)" = 1
test "$(eval_reserve req2 101 1850000000 100)" = 2
test "$(eval_reserve req3 102 1850000000 100)" = 0
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 0
test "$(eval_reserve req4 103 1850000000 100)" = 3
test "$(eval_reserve req5 104 1799999999 100)" = 5

# A rejected rate-limited attempt must not decrement stock.
docker exec "$redis" redis-cli SET "$stock" 1 >/dev/null
test "$(eval_reserve req5 104 1850000001 0)" = 7
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 1

# Expired campaign is rejected before stock and user markers are changed.
test "$(eval_reserve req5 104 1900000000 100)" = 6

echo 'PASS: Lua enforces idempotency, one request per user, inventory bounds, time windows and rate limits'
