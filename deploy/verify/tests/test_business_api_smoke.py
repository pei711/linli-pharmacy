import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError


MODULE_PATH = Path(__file__).resolve().parents[1] / "business-api-smoke.py"
SPEC = importlib.util.spec_from_file_location("business_api_smoke", MODULE_PATH)
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


class BusinessApiSmokeTest(unittest.TestCase):
    def test_full_stack_failure_trap_captures_original_command_without_xtrace(self):
        script = (MODULE_PATH.parent / "full-stack-smoke.sh").read_text(encoding="utf-8")

        self.assertIn('trap \'on_error "$?" "$BASH_COMMAND" "${BASH_LINENO[0]:-$LINENO}"\' ERR', script)
        self.assertNotIn("set -x", script)
        self.assertIn('-p"$MYSQL_ROOT_PASSWORD"', script)

    def test_full_stack_seed_import_selects_hmdp_database(self):
        script = (MODULE_PATH.parent / "full-stack-smoke.sh").read_text(encoding="utf-8")
        seed_import = next(line for line in script.splitlines()
                           if "mysql --default-character-set=utf8mb4" in line)

        self.assertIn("-uroot -p\"$MYSQL_ROOT_PASSWORD\" hmdp < /tmp/npc-demo.sql", seed_import)

    def test_full_stack_smoke_exposes_login_code_only_in_its_compose_override(self):
        script = (MODULE_PATH.parent / "full-stack-smoke.sh").read_text(encoding="utf-8")
        override = (MODULE_PATH.parent / "full-stack-smoke.compose.override.yml").read_text(
            encoding="utf-8")

        self.assertIn('compose_override="$repo_root/deploy/verify/full-stack-smoke.compose.override.yml"', script)
        self.assertIn('-f "$compose_file" -f "$compose_override"', script)
        self.assertIn('APP_AUTH_EXPOSE_CODE: "true"', override)

    def test_explicit_smoke_check_failures_preserve_their_actual_stage(self):
        script = (MODULE_PATH.parent / "full-stack-smoke.sh").read_text(encoding="utf-8")

        self.assertIn("fail_check() {", script)
        self.assertIn("failure_line=${BASH_LINENO[0]:-$LINENO}", script)
        self.assertIn("failure_command=$message", script)
        self.assertIn("GET $base/swagger-ui/ returned HTTP $http_status", script)
        self.assertIn("anonymous POST /api/v1/activities/1/reserve returned HTTP $unauth_status", script)
        self.assertIn('if [[ "$unauth_status" != 401 ]]; then', script)
        self.assertIn("expected 401", script)
        self.assertNotIn("expected 401 or 403", script)
        self.assertNotIn("exit 1", script)

    def test_openapi_401_fails_fast_as_an_authentication_error(self):
        script = (MODULE_PATH.parent / "full-stack-smoke.sh").read_text(encoding="utf-8")
        probe_loop = script.split('for attempt in $(seq 1 150); do', 1)[1].split(
            'if [[ "$api_docs_status" != 200 ]]', 1)[0]

        self.assertIn('if [[ "$api_docs_status" == 401 ]]; then', probe_loop)
        self.assertIn('documentation route must be public', probe_loop)
        self.assertIn('"${compose[@]}" logs --tail=80 backend frontend', probe_loop)
        self.assertLess(probe_loop.index('if [[ "$api_docs_status" == 401 ]]; then'),
                        probe_loop.index('sleep 2'))

    def test_swagger_401_diagnostics_compare_nginx_with_direct_backend(self):
        script = (MODULE_PATH.parent / "full-stack-smoke.sh").read_text(encoding="utf-8")

        self.assertIn("swagger_failure_diagnostics() {", script)
        self.assertIn('local response_file=$2', script)
        self.assertIn('cat "/tmp/npc-swagger-headers.txt"', script)
        self.assertIn('http://backend:8081$path', script)
        self.assertIn('"${compose[@]}" exec -T frontend wget -S -O-', script)
        self.assertIn('swagger_failure_diagnostics "/swagger-ui/" /tmp/npc-swagger-response.txt', script)
        self.assertIn('swagger_failure_diagnostics "/swagger-ui/swagger-ui-bundle.js" /tmp/npc-swagger-bundle.js', script)

    def test_nginx_forwards_authorization_for_all_api_proxy_routes(self):
        nginx_config = (MODULE_PATH.parents[2] / "AI-dianping-frontend" / "conf" / "nginx.conf")
        config = nginx_config.read_text(encoding="utf-8")

        self.assertIn("resolver 127.0.0.11 valid=5s ipv6=off;", config)
        self.assertIn("server backend:8081 resolve;", config)
        self.assertNotIn("proxy_pass http://backend:8081", config)

        for location in ("location /api/v1/", "location /api/", "location ^~ /user/"):
            with self.subTest(location=location):
                section = config.split(location, 1)[1].split("\n    }", 1)[0]
                self.assertIn("proxy_set_header Authorization $http_authorization;", section)
                self.assertIn("proxy_pass http://backend_servers", section)

    def test_swagger_root_and_assets_are_excluded_from_login_interceptor(self):
        config_path = (MODULE_PATH.parents[2] / "AI-dianping-backend" / "src" / "main" / "java"
                       / "com" / "hmdp" / "config" / "MvcConfig.java")
        config = config_path.read_text(encoding="utf-8")
        for path in ("/swagger-ui", "/swagger-ui/", "/swagger-ui/index.html",
                     "/swagger-ui/swagger-initializer.js", "/swagger-ui/swagger-ui.css",
                     "/swagger-ui/swagger-ui-bundle.js"):
            with self.subTest(path=path):
                self.assertIn(f'"{path}"', config)

        interceptor = (MODULE_PATH.parents[2] / "AI-dianping-backend" / "src" / "main" / "java"
                       / "com" / "hmdp" / "interceptor" / "LoginInterceptor.java").read_text(
                           encoding="utf-8")
        self.assertIn("isPublicDocumentationPath(request.getRequestURI(), request.getContextPath())",
                      interceptor)
        self.assertIn('path.startsWith("/swagger-ui/")', interceptor)
        self.assertIn('path.startsWith("/v3/api-docs/")', interceptor)

    def test_login_code_route_is_public_for_the_smoke_auth_flow(self):
        config_path = (MODULE_PATH.parents[2] / "AI-dianping-backend" / "src" / "main" / "java"
                       / "com" / "hmdp" / "config" / "MvcConfig.java")
        config = config_path.read_text(encoding="utf-8")
        self.assertIn('"/user/code"', config)
        controller = (MODULE_PATH.parents[2] / "AI-dianping-backend" / "src" / "main" / "java"
                      / "com" / "hmdp" / "controller" / "UserController.java").read_text(
                          encoding="utf-8")
        self.assertIn('@PostMapping("/code")', controller)

    def test_api_401_diagnostic_points_to_login_and_authorization_hops(self):
        api = smoke.Api("http://127.0.0.1:18080")
        error = HTTPError("http://127.0.0.1:18080/user/me", 401, "Unauthorized", {}, None)
        with patch.object(smoke.urllib.request, "urlopen", side_effect=error):
            with self.assertRaisesRegex(RuntimeError,
                                        r"GET /user/me returned HTTP 401.*login response token.*Authorization"):
                api.call("GET", "/user/me")

    def test_authenticated_api_calls_forward_the_raw_login_token(self):
        captured = {}

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return b'{"success":true,"data":{"id":42}}'

        def open_request(request, timeout):
            captured["path"] = request.full_url
            captured["authorization"] = request.get_header("Authorization")
            captured["timeout"] = timeout
            return Response()

        api = smoke.Api("http://127.0.0.1:18080")
        api.token = "synthetic-login-token"
        with patch.object(smoke.urllib.request, "urlopen", side_effect=open_request):
            result = api.call("GET", "/user/me")

        self.assertEqual(result, {"id": 42})
        self.assertEqual(captured["path"], "http://127.0.0.1:18080/user/me")
        self.assertEqual(captured["authorization"], "synthetic-login-token")

    def test_auth_precheck_requires_401(self):
        error = HTTPError("http://127.0.0.1/user/me", 401, "Unauthorized", {}, None)
        with patch.object(smoke.urllib.request, "urlopen", side_effect=error):
            smoke.assert_unauthenticated_profile_is_rejected("http://127.0.0.1:18080")

    def test_auth_precheck_rejects_other_http_errors(self):
        error = HTTPError("http://127.0.0.1/user/me", 502, "Bad Gateway", {}, None)
        with patch.object(smoke.urllib.request, "urlopen", side_effect=error):
            with self.assertRaisesRegex(RuntimeError, "expected 401"):
                smoke.assert_unauthenticated_profile_is_rejected("http://127.0.0.1:18080")


if __name__ == "__main__":
    unittest.main()
