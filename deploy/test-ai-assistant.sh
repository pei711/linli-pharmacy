#!/usr/bin/env bash
set -euo pipefail
base=http://127.0.0.1:18080
phone=19900000001
code_json=$(curl --fail --silent --show-error -X POST "$base/user/code?phone=$phone")
code=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["code"])' <<<"$code_json")
login_json=$(curl --fail --silent --show-error -H 'Content-Type: application/json' -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" "$base/user/login")
token=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"])' <<<"$login_json")
reply=$(curl --fail --silent --show-error -H "authorization: $token" -H 'Content-Type: application/json' -d '{"message":"???????????????"}' "$base/api/v1/ai/assistant/chat")
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["success"] is True, r; d=r["data"]; assert d["source"]=="RULES_FALLBACK", d; assert d["answer"], d; print("authenticated_ai_endpoint=PASS"); print("source="+d["source"]); print("answer_nonempty=PASS")' <<<"$reply"
curl --fail --silent --show-error -X POST -H "authorization: $token" "$base/user/logout" >/dev/null
