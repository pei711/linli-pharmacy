#!/usr/bin/env bash
set -euo pipefail

# Exercises Redis pre-debit followed by a real MySQL outage. The backend's
# recovery job must expire the stale INITIALIZING request and release Redis.
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
project=${1:-}
activity_id=${2:-}
if [[ ! "$project" =~ ^npc-db-failure-[a-zA-Z0-9_-]{1,45}$ ]] ||
   [[ ! "$activity_id" =~ ^[1-9][0-9]*$ ]] || [[ -z "${NPC_RESERVATION_TOKEN:-}" ]]; then
  echo "usage: NPC_RESERVATION_TOKEN=<isolated-user-token> $0 npc-db-failure-<suffix> <published-activity-id>" >&2
  exit 2
fi

compose=(docker compose -p "$project" -f "$repo_root/deploy/server-test.compose.yml" \
  -f "$repo_root/deploy/verify/mysql-outage-after-redis.compose.override.yml")
sql() {
  "${compose[@]}" exec -T mysql sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot --batch --skip-column-names hmdp -e "$1"' \
    sh "$1"
}
redis() { "${compose[@]}" exec -T redis redis-cli --raw "$@"; }

request_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
stock_key="pharmacy:seckill:stock:$activity_id"
reservation_key="pharmacy:seckill:reservation:$request_id"
response_file=$(mktemp)
status_file=$(mktemp)
curl_pid=""
mysql_stopped=0
cleanup() {
  if (( mysql_stopped )); then "${compose[@]}" start mysql >/dev/null 2>&1 || true; fi
  if [[ -n "$curl_pid" ]]; then kill "$curl_pid" >/dev/null 2>&1 || true; fi
  rm -f "$response_file" "$status_file"
}
trap cleanup EXIT

[[ "$(sql "SELECT status FROM seckill_activity WHERE id=$activity_id")" == PUBLISHED ]] || {
  echo "activity must be PUBLISHED" >&2; exit 2;
}
before=$(redis GET "$stock_key")
[[ "$before" =~ ^[1-9][0-9]*$ ]] || { echo "Redis activity stock must be positive" >&2; exit 2; }

curl --silent --show-error --output "$response_file" --write-out '%{http_code}' \
  -X POST "http://127.0.0.1:18080/api/v1/activities/$activity_id/reserve" \
  -H "Authorization: $NPC_RESERVATION_TOKEN" -H "Idempotency-Key: $request_id" \
  -H 'Content-Type: application/json' -d '{"quantity":1}' >"$status_file" &
curl_pid=$!

accepted=0
for attempt in $(seq 1 40); do
  if [[ "$(redis HGET "$reservation_key" state 2>/dev/null || true)" == PROCESSING ]]; then accepted=1; break; fi
  if ! kill -0 "$curl_pid" 2>/dev/null; then break; fi
  sleep 0.25
done
if (( ! accepted )); then
  echo "reservation did not reach Redis pre-debit; request_id=$request_id" >&2
  exit 1
fi

redis_after_debit=$(redis GET "$stock_key")
[[ "$redis_after_debit" == "$((before - 1))" ]] || { echo "Redis pre-debit was not exactly one" >&2; exit 1; }
"${compose[@]}" stop --timeout 1 mysql >/dev/null
mysql_stopped=1
sleep 12
"${compose[@]}" start mysql >/dev/null
for attempt in $(seq 1 60); do
  if "${compose[@]}" exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -uroot --silent' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
"${compose[@]}" exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqladmin ping -uroot --silent' >/dev/null
mysql_stopped=0

wait "$curl_pid" || true
curl_pid=""
http_status=$(cat "$status_file")

for attempt in $(seq 1 60); do
  row=$(sql "SELECT CONCAT(status,':',reservation_state) FROM seckill_request WHERE request_id='$request_id'" 2>/dev/null || true)
  stock=$(redis GET "$stock_key")
  if [[ "$row" == EXPIRED:RELEASED && "$stock" == "$before" ]]; then break; fi
  sleep 2
done

if [[ "$row" != EXPIRED:RELEASED || "$stock" != "$before" ]]; then
  echo "FAIL: MySQL outage recovery did not restore the reservation (request_id=$request_id state=$row stock=$stock expected=$before HTTP=$http_status)" >&2
  exit 1
fi
user_id=$(sql "SELECT user_id FROM seckill_request WHERE request_id='$request_id'")
user_marker=$(redis HGET "pharmacy:seckill:user:$activity_id" "$user_id")
reservation_state=$(redis HGET "$reservation_key" state)
[[ -z "$user_marker" && "$reservation_state" == EXPIRED ]] || {
  echo "FAIL: Redis user/reservation markers were not cleaned (request_id=$request_id)" >&2; exit 1;
}

printf '{"passed":true,"request_id":"%s","activity_id":%s,"http_status":"%s","redis_stock_before":%s,"redis_stock_after":%s,"mysql_state":"%s","checks":{"redis_predebit_observed":true,"mysql_outage_injected":true,"expired_request_released":true,"stock_restored_exactly":true,"user_marker_removed":true},"cleanup":"MySQL restored; temporary request retained for audit; destroy dedicated Compose project and volumes after saving report"}\n' \
  "$request_id" "$activity_id" "$http_status" "$before" "$stock" "$row"
