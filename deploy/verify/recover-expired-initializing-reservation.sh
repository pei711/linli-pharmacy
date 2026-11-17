#!/usr/bin/env bash
set -euo pipefail

# Inject the crash window after Redis Lua pre-debit but before the MySQL
# INITIALIZING -> PROCESSING transaction, then observe the real recovery job.
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
compose_file="$repo_root/deploy/server-test.compose.yml"
project=${1:-}
activity_id=${2:-}
user_id=${3:-}
if [[ ! "$project" =~ ^(neighbor-pharmacy-test|npc-[a-zA-Z0-9_-]{1,55})$ ]] ||
   [[ ! "$activity_id" =~ ^[1-9][0-9]*$ ]] || [[ ! "$user_id" =~ ^[1-9][0-9]*$ ]]; then
  echo "usage: $0 <isolated-compose-project> <activity-id> <unused-user-id>" >&2
  exit 2
fi

compose=(docker compose -p "$project" -f "$compose_file" exec -T)
request_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
redis_key="pharmacy:seckill:stock:$activity_id"
users_key="pharmacy:seckill:user:$activity_id"
reservation_key="pharmacy:seckill:reservation:$request_id"
pending_key="pharmacy:seckill:pending:$activity_id"

sql() {
  "${compose[@]}" mysql sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot --batch --skip-column-names hmdp -e "$1"' \
    sh "$1"
}
redis() { "${compose[@]}" redis redis-cli --raw "$@"; }

activity=$(sql "SELECT status FROM seckill_activity WHERE id=$activity_id")
[[ "$activity" == PUBLISHED ]] || { echo "activity must be PUBLISHED" >&2; exit 2; }
if [[ -n "$(sql "SELECT request_id FROM seckill_request WHERE activity_id=$activity_id AND user_id=$user_id AND status IN ('INITIALIZING','PROCESSING','SUCCESS') LIMIT 1")" ]]; then
  echo "user already has an active/successful request for this activity" >&2
  exit 2
fi
if [[ -n "$(redis HGET "$users_key" "$user_id")" ]]; then
  echo "user already has a Redis reservation for this activity" >&2
  exit 2
fi
before=$(redis GET "$redis_key")
[[ "$before" =~ ^[1-9][0-9]*$ ]] || { echo "activity Redis stock must be positive" >&2; exit 2; }

# Quantity 1 makes the compensating increment observable and bounded.
sql "INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,status,reservation_state,expires_at,failure_code) VALUES ('$request_id',$activity_id,$user_id,1,'INITIALIZING','NONE',DATE_SUB(NOW(),INTERVAL 2 SECOND),'VERIFY_INJECTED')"
redis DECR "$redis_key" >/dev/null
redis HSET "$users_key" "$user_id" "$request_id" >/dev/null
redis HSET "$reservation_key" requestId "$request_id" activityId "$activity_id" userId "$user_id" quantity 1 state PROCESSING expiresAt 1 >/dev/null
redis ZADD "$pending_key" 1 "$request_id" >/dev/null

for attempt in $(seq 1 45); do
  row=$(sql "SELECT CONCAT(status,':',reservation_state) FROM seckill_request WHERE request_id='$request_id'")
  stock=$(redis GET "$redis_key")
  user_reservation=$(redis HGET "$users_key" "$user_id")
  reservation_state=$(redis HGET "$reservation_key" state)
  if [[ "$row" == EXPIRED:RELEASED && "$stock" == "$before" && -z "$user_reservation" && "$reservation_state" == EXPIRED ]]; then
    redis DEL "$reservation_key" >/dev/null
    redis ZREM "$pending_key" "$request_id" >/dev/null
    printf '{"passed":true,"request_id":"%s","activity_id":%s,"user_id":%s,"redis_stock_before":%s,"redis_stock_after":%s,"mysql_state":"%s","checks":{"mysql_expired_released":true,"stock_restored_exactly":true,"user_limit_marker_removed":true,"redis_reservation_expired":true},"cleanup":"Redis probe keys removed; MySQL EXPIRED/RELEASED row retained for audit"}\n' \
      "$request_id" "$activity_id" "$user_id" "$before" "$stock" "$row"
    exit 0
  fi
  sleep 2
done

user_reservation=$(redis HGET "$users_key" "$user_id" || true)
reservation_state=$(redis HGET "$reservation_key" state || true)
pending_score=$(redis ZSCORE "$pending_key" "$request_id" || true)
mysql_detail=$(sql "SELECT JSON_OBJECT('status',status,'reservation_state',reservation_state,'failure_code',failure_code,'expires_at',expires_at) FROM seckill_request WHERE request_id='$request_id'" || true)
python3 -c '
import json, sys
(_, request_id, activity_id, user_id, before, after, mysql_state, user_marker,
 reservation_state, pending_score, mysql_detail) = sys.argv
try:
    mysql_detail = json.loads(mysql_detail) if mysql_detail else None
except json.JSONDecodeError:
    pass
print(json.dumps({
    "passed": False,
    "request_id": request_id,
    "activity_id": int(activity_id),
    "user_id": int(user_id),
    "redis_stock_before": before,
    "redis_stock_after": after,
    "mysql_state": mysql_state,
    "mysql_detail": mysql_detail,
    "redis_user_marker": user_marker,
    "redis_reservation_state": reservation_state,
    "pending_zset_score": pending_score,
    "checks": {
        "mysql_expired_released": mysql_state == "EXPIRED:RELEASED",
        "stock_restored_exactly": after == before,
        "user_limit_marker_removed": not user_marker,
        "redis_reservation_expired": reservation_state == "EXPIRED",
    },
    "cleanup": "Probe keys retained for investigation; MySQL audit row retained"
}, ensure_ascii=False, indent=2))
' "$request_id" "$activity_id" "$user_id" "$before" "${stock:-}" \
  "${row:-}" "${user_reservation:-}" "${reservation_state:-}" "${pending_score:-}" "${mysql_detail:-}"
echo "recovery did not converge; probe details emitted as JSON; request $request_id retained for investigation" >&2
exit 1
