#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/dev-loop/battery-driver.py" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
driver_path="$(realpath "$1")"
driver_dir="$(dirname "$driver_path")"
patch_path="$repo_root/deploy/verify/battery-driver-run4.patch"

verify_workspace() {
  python3 - "$repo_root" <<'PY'
import pathlib
import json
import sys

root = pathlib.Path(sys.argv[1])
sources = {
    "reservation reconciliation": (root / "deploy/verify/reconcile-reservations.py").read_text(encoding="utf-8"),
    "full-stack smoke": (root / "deploy/verify/full-stack-smoke.sh").read_text(encoding="utf-8"),
    "business API smoke": (root / "deploy/verify/business-api-smoke.py").read_text(encoding="utf-8"),
    "login interceptor": (root / "AI-dianping-backend/src/main/java/com/hmdp/interceptor/LoginInterceptor.java").read_text(encoding="utf-8"),
    "cache invalidation verifier": (root / "deploy/verify/cache-invalidation.py").read_text(encoding="utf-8"),
    "AI evaluation fixtures": (root / "db/demo/006-ai-evaluation-fixtures.sql").read_text(encoding="utf-8"),
    "AI evaluation verifier": (root / "deploy/verify/ai-operations-evaluate.py").read_text(encoding="utf-8"),
    "AI evaluation gold": (root / "deploy/verify/ai-operations-gold.json").read_text(encoding="utf-8"),
}
gold_path = root / "deploy/verify/ai-operations-gold.json"
try:
    gold = json.loads(gold_path.read_text(encoding="utf-8"))
except (OSError, ValueError) as error:
    raise SystemExit(f"AI evaluation gold is not valid JSON: {error}")
required = {
    "reservation reconciliation": (
        "hmdp -e \"$1\"", "pending_requests", "failure_code_counts",
        "settlement_timeout_seconds", "failed_requests_are_explained_by_sold_out",
    ),
    "full-stack smoke": (
        "fail_check()", "anonymous POST /api/v1/activities/1/reserve",
        "hmdp < /tmp/npc-demo.sql", "swagger_failure_diagnostics()",
        "http://backend:8081$path", 'if [[ "$api_docs_status" == 401 ]]; then',
        "documentation route must be public",
    ),
    "business API smoke": (
        "assert_unauthenticated_profile_is_rejected", "api.token = api.call(\"POST\", \"/user/login\"",
        "SMOKE auth step 3/3",
    ),
    "login interceptor": (
        "isPublicDocumentationPath(request.getRequestURI(), request.getContextPath())",
        'path.startsWith("/swagger-ui/")', 'path.startsWith("/v3/api-docs/")',
    ),
    "cache invalidation verifier": (
        "/user/code", "backend_logs", "redis_hit_avoids_mysql",
        "bloom_allows_legal_shop_on_both_replicas", "probe_bloom_allows_shop",
        "lost_pubsub_ttl_falls_back_to_mysql", "clear_previous_report(args.output)",
        '"stack_startup"', "write_startup_failure", "capture_compose_logs(",
    ),
    "AI evaluation fixtures": (
        "DELETE FROM tb_shop_owner", "19900000101", "19900000102",
        "AI-EVAL-A-SALE-01", "AI-EVAL-B-SALE-01",
        "store_id = VALUES(store_id)", "medicine_id = VALUES(medicine_id)",
    ),
    "AI evaluation verifier": (
        "conversation_id=f\"eval-{evaluation_run_id}-{case_id}\"",
        "expected_tool_parameters", "exact_fixture_numbers_after_anchor",
        "answer, case_gold.get(\"exact_number_after\", {}), product_anchors",
        "tenant_owner_token_selected", "def token_for_case(", "tenant_token_owner",
        'if case_id == "OPS-15":', 'expected_token_owner = "B" if case_id == "OPS-15" else "A"',
        'parser.add_argument("--store-b-token-env", required=True',
    ),
    "AI evaluation gold": (
        '"OPS-11"', '"无批次商品"', '"不能计算"', '"没有销售记录"',
        '"OPS-17"', '"AI评估A店布洛芬"', '"AI评估B店维生素C"',
    ),
}
missing = [f"{name}: {marker}" for name, markers in required.items()
           for marker in markers if marker not in sources[name]]
if missing:
    raise SystemExit("Workspace is missing Run 4 verification fixes: " + "; ".join(missing))
ops11_groups = gold.get("OPS-11", {}).get("contains_any", [])
normalized_ops11_groups = {frozenset(group) for group in ops11_groups
                           if isinstance(group, list) and all(isinstance(term, str) for term in group)}
required_ops11_groups = {
    frozenset({"AI评估A店无批次商品", "无批次商品"}),
    frozenset({"无法计算", "不能计算", "没有销售记录"}),
}
if normalized_ops11_groups != required_ops11_groups:
    raise SystemExit("OPS-11 gold must require one product-name alternative and one no-sales/calculation alternative; "
                     f"found {ops11_groups!r}")
required_ai_gold = {
    "OPS-05": {
        "contains_any": [["AI评估A店无批次商品", "无批次商品"]],
        "no_replenishment_quantity_after": ["无批次商品"],
        "not_contains_after_anchor": {"无批次商品": ["建议补", "建议采购"]},
    },
    "OPS-06": {
        "contains_any": [["AI评估A店布洛芬", "布洛芬"]],
        "exact_number_after": {"AI评估A店布洛芬": {"value": 8, "aliases": ["布洛芬"]}},
    },
    "OPS-09": {
        "contains": ["AI评估A店布洛芬"],
        "tool_parameters": {"targetDays": 30},
        "exact_number_after": {"AI评估A店布洛芬": 22},
    },
    "OPS-10": {
        "contains": ["AI评估A店布洛芬"],
        "exact_number_after": {"AI评估A店布洛芬": 13},
    },
    "OPS-13": {"contains": ["店主审核"]},
    "OPS-15": {
        "contains": ["AI评估B店维生素C"],
        "exact_number_after": {"AI评估B店维生素C": 4},
        "not_contains": ["AI评估A店布洛芬"],
    },
    "OPS-16": {
        "contains": ["AI评估A店布洛芬"],
        "exact_number_after": {"AI评估A店布洛芬": 13},
        "not_contains": ["AI评估B店维生素C"],
    },
}
for case_id, expected in required_ai_gold.items():
    if gold.get(case_id) != expected:
        raise SystemExit(f"{case_id} gold does not match the verified Run 4 fixture contract: "
                         f"expected {expected!r}, found {gold.get(case_id)!r}")
ops17 = gold.get("OPS-17", {})
if (ops17.get("contains", []) or ops17.get("contains_any", [])
        or ops17.get("exact_number_after", {})
        or ops17.get("not_contains") != ["AI评估B店维生素C"]):
    raise SystemExit("OPS-17 gold must allow minimal refusal without inventory disclosure and forbid B-store data")
print("Workspace Run 4 verification fixes are present.")
PY
}

if [[ "$(basename "$driver_path")" != "battery-driver.py" || ! -f "$driver_path" ]]; then
  echo "Expected an existing battery-driver.py file: $driver_path" >&2
  exit 2
fi

verify_workspace

verify_driver() {
  python3 - "$driver_path" <<'PY'
import ast
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
tree = ast.parse(source, filename=str(path))
functions = {node.name: ast.get_source_segment(source, node)
             for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
function_nodes = [node for node in tree.body
                  if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))]
# Keep every direct mysql client invocation visible to this preflight. The
# battery driver is expected to have exactly one parameterized query helper
# and one fixture import; a future one-off `mysql -e` would otherwise bypass
# the hmdp selection checks and fail with ERROR 1046 on a fresh stack.
mysql_invocations = []
for line_number, line in enumerate(source.splitlines(), 1):
    if re.search(r"\bmysql\s+(?:--|-)", line):
        owner = next((node.name for node in function_nodes
                      if node.lineno <= line_number <= node.end_lineno), None)
        mysql_invocations.append((line_number, owner, line.strip()))
if len(mysql_invocations) != 2 or {owner for _, owner, _ in mysql_invocations} != {"sql", "import_seed"}:
    raise SystemExit("Battery driver must keep direct mysql calls in sql() and import_seed(); "
                     f"found {mysql_invocations!r}")
required = {
    # Every ad-hoc query in the driver goes through sql(); select the schema
    # there so status polling, load setup, and fault injection cannot fail with
    # MySQL ERROR 1046. The sole direct SQL path imports a fixture file and
    # must also name the schema explicitly.
    "SQL helper selects hmdp": ("sql", "--skip-column-names hmdp -e \"$1\""),
    "fixture import selects hmdp": ("import_seed", "mysql --default-character-set=utf8mb4 -uroot hmdp"),
    "load phase uses its rate-limit override": ("p_load", "load-test.compose.override.yml"),
    "load phase reconciles settled requests": ("p_load", '"--settlement-timeout-seconds", "300"'),
    "cache boot scales before first startup": ("boot_stack", 'up_args.extend(["--scale", "backend=2"])'),
    "cache startup is selected by its override": ("boot_stack", "cache-invalidation.compose.override.yml"),
    "cache boot avoids ingress probes before backend readiness": (
        "boot_stack", "if wait and not is_cache_stack:"),
    "cache startup is guarded by cleanup": ("p_cache", "try:\n        ensure_kafka_volume(project)"),
    "cache seed precedes Bloom snapshot": (
        "p_cache", 'import_seed(project, "db/demo/005-pharmacy-mvp-demo.sql")\n'
                   '        # BloomFilterInitializer snapshots shops at backend startup. Seed the\n'
                   '        # legal shop before starting Spring so the cache verifier can check\n'
                   '        # for Bloom false negatives on both replicas.\n'
                   '        boot_stack(project, files)'),
    "cache seed precedes Bloom snapshot": (
        "p_cache", 'import_seed(project, "db/demo/005-pharmacy-mvp-demo.sql")\n'
                   '        # BloomFilterInitializer snapshots shops at backend startup. Seed the\n'
                   '        # legal shop before starting Spring so the cache verifier can check\n'
                   '        # for Bloom false negatives on both replicas.\n'
                   '        boot_stack(project, files)'),
    "cache startup failures collect logs": ("p_cache", 'for service in ("backend", "frontend", "mysql", "redis", "kafka")'),
    "expired-initializing uses a stocked recovery activity": (
        "p_fault", '_, recovery_activity = make_activity(api, quantity=10, batch_qty=20, minutes=30)\n'
                   '        r = run(["bash", "deploy/verify/recover-expired-initializing-reservation.sh",\n'
                   '                 project, str(recovery_activity), fresh_uid]'),
    "AI fixture owner A": ("p_ai_eval", 'api_a.login("19900000101")'),
    "AI fixture owner B": ("p_ai_eval", 'login("19900000102")'),
    "AI fixture preflight validates product and batch ownership": (
        "p_ai_eval", 'expected_fixture = "2:2:2:3:3:2:2:2:8:4:30:12"'),
    "AI fixture preflight checks product and batch foreign keys": (
        "p_ai_eval", "store_id=990010 AND sale_category='OTC'"),
    "AI fixture preflight counts all owner links": ("p_ai_eval", "u.phone IN ('19900000101','19900000102')"),
}
missing = [label for label, (function, marker) in required.items()
           if function not in functions or marker not in functions[function]]
if missing:
    raise SystemExit("Battery driver is missing required Run 4/5 fixes: " + ", ".join(missing))
PY
}

if verify_driver 2>/dev/null; then
  echo "Battery driver already contains all Run 4/5 fixes and parses successfully."
  exit 0
fi

if ! patch --batch --forward --dry-run -p1 -d "$driver_dir" < "$patch_path"; then
  echo "Battery driver patch does not apply cleanly; left the driver unchanged." >&2
  exit 1
fi

patch --batch --forward -p1 -d "$driver_dir" < "$patch_path"
verify_driver
echo "Battery driver patched, all Run 4/5 markers verified, and Python syntax is valid."
