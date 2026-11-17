#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
compose_file="$repo_root/deploy/server-test.compose.yml"
compose_override="$repo_root/deploy/verify/full-stack-smoke.compose.override.yml"
: "${DB_PASSWORD:?Set DB_PASSWORD from the private server environment}"
port=18080
project="npc-verify-$(date +%s)-$$"
compose=(docker compose -p "$project" --project-directory "$repo_root" \
  -f "$compose_file" -f "$compose_override")
artifact="$repo_root/app.jar"
failure_status=0
failure_line=""
failure_command=""
if [[ -e "$artifact" ]]; then
  echo "Refusing to overwrite existing $artifact" >&2
  exit 2
fi
cleanup() {
  status=$?
  trap - EXIT ERR
  if [[ $status -ne 0 ]]; then
    echo "Full-stack smoke failed (exit $status); preserving diagnostics before stack cleanup:" >&2
    "${compose[@]}" ps -a >&2 || true
    "${compose[@]}" logs --tail=120 backend frontend mysql redis kafka >&2 || true
  fi
  rm -f "$artifact"
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [[ $status -ne 0 && -n "$failure_command" ]]; then
    printf 'Full-stack smoke failed at %s:%s (exit %s): %s\n' \
      "$repo_root/deploy/verify/full-stack-smoke.sh" "$failure_line" \
      "$failure_status" "$failure_command" >&2
  fi
}
on_error() {
  failure_status=$1
  failure_command=$2
  failure_line=$3
  # The EXIT cleanup may run commands that fail while gathering diagnostics.
  # Disable ERR first so those failures cannot replace the original command.
  trap - ERR
  exit "$failure_status"
}
fail_check() {
  local message=$1
  local status=${2:-1}
  failure_status=$status
  failure_line=${BASH_LINENO[0]:-$LINENO}
  failure_command=$message
  echo "Full-stack smoke check failed at line $failure_line: $message" >&2
  exit "$status"
}
# Capture the failing command at trap entry. Reading BASH_COMMAND inside the
# handler reports the handler's own `exit`, which previously hid the cause.
# Do not enable xtrace here: commands later in this script expand DB_PASSWORD.
trap 'on_error "$?" "$BASH_COMMAND" "${BASH_LINENO[0]:-$LINENO}"' ERR
trap cleanup EXIT

deploy/memory-preflight.sh "$compose_file"
echo "PASS: memory preflight"
deploy/verify/build-test.sh --docker
echo "PASS: Maven build and tests"
cp AI-dianping-backend/target/*.jar "$artifact"
# Fresh Kafka named volumes are root-owned; the Kafka image runs as UID 1000
# and cannot write KRaft logs (see docs/SERVER_TEST.md). Pre-create + chown.
docker volume create "${project}_kafka_data" >/dev/null
chown 1000:1000 "/var/lib/docker/volumes/${project}_kafka_data/_data"
"${compose[@]}" config >/dev/null
echo "Starting isolated full-stack Compose services"
"${compose[@]}" up -d --build --wait
echo "PASS: isolated full-stack services healthy"

base="http://127.0.0.1:$port"
swagger_failure_diagnostics() {
  local path=$1
  local response_file=$2
  local upstream_output
  echo "Swagger diagnostic for $path:" >&2
  echo "  Nginx response headers:" >&2
  cat "/tmp/npc-swagger-headers.txt" >&2 2>/dev/null || true
  echo "  Nginx response body (first 500 bytes):" >&2
  head -c 500 "$response_file" >&2 2>/dev/null || true
  echo >&2
  echo "  Direct frontend-to-backend response:" >&2
  upstream_output=$("${compose[@]}" exec -T frontend wget -S -O- --timeout=8 \
    "http://backend:8081$path" 2>&1 || true)
  printf '%s\n' "$upstream_output" | head -c 1500 >&2 || true
  echo >&2
  echo "  Backend/frontend container logs:" >&2
  "${compose[@]}" logs --tail=80 backend frontend >&2 || true
}
api_docs_status="not-requested"
rm -f /tmp/npc-api-docs.json
for attempt in $(seq 1 150); do
  api_docs_status=$(curl --silent --show-error --location -o /tmp/npc-api-docs.json \
    -w '%{http_code}' "$base/v3/api-docs" 2>/tmp/npc-api-docs-curl-error || true)
  if [[ "$api_docs_status" == 200 ]] && [[ -s /tmp/npc-api-docs.json ]]; then break; fi
  # A 401 is an authorization/configuration failure, not an application
  # startup delay. Fail immediately so the battery identifies the public
  # documentation route as the failing smoke stage instead of waiting 300s.
  if [[ "$api_docs_status" == 401 ]]; then
    echo "GET $base/v3/api-docs returned HTTP 401; expected public OpenAPI JSON" >&2
    cat /tmp/npc-api-docs-curl-error >&2 || true
    echo "Backend/frontend authentication diagnostics:" >&2
    "${compose[@]}" logs --tail=80 backend frontend >&2 || true
    fail_check "GET $base/v3/api-docs returned HTTP 401 (documentation route must be public)"
  fi
  sleep 2
done
if [[ "$api_docs_status" != 200 ]] || [[ ! -s /tmp/npc-api-docs.json ]]; then
  echo "GET $base/v3/api-docs never returned a non-empty HTTP 200 response after 300s (last status: $api_docs_status)" >&2
  cat /tmp/npc-api-docs-curl-error >&2 || true
  if [[ -s /tmp/npc-api-docs.json ]]; then
    echo "Last response body (first 500 bytes):" >&2
    head -c 500 /tmp/npc-api-docs.json >&2
    echo >&2
  fi
  echo "backend/frontend logs:" >&2
  "${compose[@]}" logs --tail=80 frontend >&2 || true
  "${compose[@]}" logs --tail=80 backend >&2 || true
  fail_check "GET $base/v3/api-docs did not return a non-empty HTTP 200 (last status: $api_docs_status)"
fi
python3 - /tmp/npc-api-docs.json <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    doc = json.load(f)
assert isinstance(doc.get("paths"), dict) and doc["paths"], "OpenAPI paths missing"
assert "/api/v1/activities/{activityId}/reserve" in doc["paths"], "reservation endpoint missing"
print(f"PASS: OpenAPI JSON contains {len(doc['paths'])} paths")
PY
# The frontend can briefly refuse connections while the stack settles
# right after the api-docs poll succeeds (container restart window).
# Retry like the poll above instead of failing the whole smoke run on
# one transient connect error; the 200 + content checks below still apply.
fetch_with_retry() {
  local url=$1 out=$2 headers=${3:-}
  local status=000 attempt
  for attempt in $(seq 1 15); do
    if [[ -n "$headers" ]]; then
      status=$(curl --silent --show-error --location -D "$headers" \
        -o "$out" -w '%{http_code}' "$url" || true)
    else
      status=$(curl --silent --show-error --location \
        -o "$out" -w '%{http_code}' "$url" || true)
    fi
    [[ "$status" == 200 ]] && break
    sleep 2
  done
  printf '%s' "$status"
}
http_status=$(fetch_with_retry "$base/swagger-ui/" /tmp/npc-swagger-response.txt /tmp/npc-swagger-headers.txt)
if [[ "$http_status" != 200 ]]; then
  echo "Swagger UI request returned HTTP $http_status: $base/swagger-ui/" >&2
  swagger_failure_diagnostics "/swagger-ui/" /tmp/npc-swagger-response.txt
  fail_check "GET $base/swagger-ui/ returned HTTP $http_status"
fi
grep -q 'swagger-ui' /tmp/npc-swagger-response.txt
http_status=$(fetch_with_retry "$base/swagger-ui/swagger-ui-bundle.js" /tmp/npc-swagger-bundle.js /tmp/npc-swagger-headers.txt)
if [[ "$http_status" != 200 ]]; then
  echo "Swagger UI bundle request returned HTTP $http_status: $base/swagger-ui/swagger-ui-bundle.js" >&2
  swagger_failure_diagnostics "/swagger-ui/swagger-ui-bundle.js" /tmp/npc-swagger-bundle.js
  fail_check "GET $base/swagger-ui/swagger-ui-bundle.js returned HTTP $http_status"
fi
bundle_bytes=$(wc -c < /tmp/npc-swagger-bundle.js)
if [[ $bundle_bytes -le 10000 ]]; then
  fail_check "Swagger UI bundle is too small ($bundle_bytes bytes)"
fi
unauth_status=$(curl --silent -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"quantity":1}' "$base/api/v1/activities/1/reserve")
if [[ "$unauth_status" != 401 ]]; then
  echo "Anonymous POST $base/api/v1/activities/1/reserve returned HTTP $unauth_status; expected 401" >&2
  "${compose[@]}" logs --tail=80 frontend backend >&2 || true
  fail_check "anonymous POST /api/v1/activities/1/reserve returned HTTP $unauth_status (expected 401)"
fi
echo "PASS: Swagger UI HTML and JavaScript bundle load; unauthenticated reservation API returns $unauth_status"

# Seed only the synthetic owner/shop into this disposable Compose project's volume.
"${compose[@]}" cp db/demo/005-pharmacy-mvp-demo.sql mysql:/tmp/npc-demo.sql
"${compose[@]}" exec -T mysql sh -c \
  'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" hmdp < /tmp/npc-demo.sql'
python3 deploy/verify/business-api-smoke.py --base-url "$base" --store-id 900010
