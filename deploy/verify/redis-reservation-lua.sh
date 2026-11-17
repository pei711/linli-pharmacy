#!/usr/bin/env bash
set -euo pipefail

# Exercise the production reservation/release Lua scripts against isolated keys
# in the documented Compose test stack. This does not exercise MySQL or Kafka.
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
compose_file="$repo_root/deploy/server-test.compose.yml"
project=${1:-}
if [[ ! "$project" =~ ^(neighbor-pharmacy-test|npc-[a-zA-Z0-9_-]{1,55})$ ]]; then
  echo "usage: $0 <neighbor-pharmacy-test|npc-...>" >&2
  exit 2
fi

compose=(docker compose -p "$project" -f "$compose_file" exec -T redis)
suffix="verify:$(date +%s):$$"
prefix="pharmacy:seckill:$suffix"
stock="$prefix:stock"
status="$prefix:status"
meta="$prefix:activity"
users="$prefix:users"
pending="$prefix:pending"
request_a="$prefix:reservation:req-a"
request_b="$prefix:reservation:req-b"
request_c="$prefix:reservation:req-c"
rate_activity="$prefix:rate:activity"
rate_ip="$prefix:rate:ip"
rate_user_a="$prefix:rate:user:a"
rate_user_b="$prefix:rate:user:b"
rate_user_c="$prefix:rate:user:c"
reserve_file="/tmp/npc-reserve-$suffix.lua"
release_file="/tmp/npc-release-$suffix.lua"

redis() { "${compose[@]}" redis-cli --raw "$@"; }
cleanup() {
  redis DEL "$stock" "$status" "$meta" "$users" "$pending" "$request_a" "$request_b" "$request_c" \
    "$rate_activity" "$rate_ip" "$rate_user_a" "$rate_user_b" "$rate_user_c" >/dev/null 2>&1 || true
  "${compose[@]}" rm -f "$reserve_file" "$release_file" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" sh -c "cat > '$reserve_file'" < "$repo_root/AI-dianping-backend/src/main/resources/pharmacy/reserve.lua"
"${compose[@]}" sh -c "cat > '$release_file'" < "$repo_root/AI-dianping-backend/src/main/resources/pharmacy/release-reservation.lua"

now=$(date +%s)
start=$((now - 60))
end=$((now + 3600))
expiry=$((now + 120))
redis SET "$stock" 2 >/dev/null
redis SET "$status" PUBLISHED >/dev/null
redis HSET "$meta" startsAt "$start" endsAt "$end" perUserLimit 1 >/dev/null

reserve() {
  local request_id=$1 user_id=$2 reservation_key=$3 rate_user=$4
  redis --eval "$reserve_file" "$stock" "$status" "$meta" "$users" "$reservation_key" "$pending" \
    "$rate_activity" "$rate_ip" "$rate_user" , \
    "$request_id" "$user_id" 1 "$now" "$expiry" 9001 2 100 100 100 604800
}
release() {
  redis --eval "$release_file" "$stock" unused unused "$users" "$request_a" "$pending" , req-a 101
}

[[ "$(reserve req-a 101 "$request_a" "$rate_user_a")" == 0 ]]
[[ "$(redis GET "$stock")" == 1 ]]
[[ "$(reserve req-a 101 "$request_a" "$rate_user_a")" == 1 ]]
[[ "$(reserve req-b 101 "$request_b" "$rate_user_b")" == 2 ]]
[[ "$(reserve req-b 102 "$request_b" "$rate_user_b")" == 0 ]]
[[ "$(redis GET "$stock")" == 0 ]]
[[ "$(reserve req-c 103 "$request_c" "$rate_user_c")" == 3 ]]
[[ "$(redis GET "$stock")" == 0 ]]

[[ "$(release)" == 1 ]]
[[ "$(redis GET "$stock")" == 1 ]]
[[ "$(redis HGET "$users" 101)" == "" ]]
[[ "$(release)" == 1 ]]
[[ "$(redis GET "$stock")" == 1 ]]
[[ "$(redis HGET "$users" 102)" == req-b ]]

echo '{"passed":true,"checks":{"atomic_predebit":true,"idempotent_replay":true,"one_per_user":true,"sold_out_rejection":true,"release_once":true},"scope":"Redis Lua only; MySQL/Kafka not exercised"}'
