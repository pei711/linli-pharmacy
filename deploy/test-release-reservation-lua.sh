#!/bin/sh
# Tests once-only Redis stock restoration under test-only keys.
set -eu

redis=neighbor-pharmacy-test-redis-1
script=/tmp/pharmacy-release-test.lua
prefix=pharmacy:seckill:test:release:20261116
stock="${prefix}:stock"
users="${prefix}:users"
request="${prefix}:request:req1"
pending="${prefix}:pending"

docker cp /opt/neighbor-pharmacy-cloud/AI-dianping-backend/src/main/resources/pharmacy/release-reservation.lua "$redis:$script"
cleanup() { docker exec "$redis" redis-cli DEL "$stock" "$users" "$request" "$pending" >/dev/null; }
trap cleanup EXIT
cleanup

docker exec "$redis" redis-cli SET "$stock" 4 >/dev/null
docker exec "$redis" redis-cli HSET "$users" 501 req1 >/dev/null
docker exec "$redis" redis-cli HSET "$request" requestId req1 userId 501 quantity 2 state PROCESSING >/dev/null
docker exec "$redis" redis-cli ZADD "$pending" 1900000000 req1 >/dev/null

release() {
  docker exec "$redis" redis-cli --raw --eval "$script" "$stock" unused unused "$users" "$request" "$pending" , req1 501
}

test "$(release)" = 1
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 6
test "$(docker exec "$redis" redis-cli --raw HGET "$users" 501)" = ''
test "$(release)" = 1
test "$(docker exec "$redis" redis-cli --raw GET "$stock")" = 6

echo 'PASS: timeout compensation restores stock once and removes the matching per-user marker'
