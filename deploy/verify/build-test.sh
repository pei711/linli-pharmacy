#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
backend="$repo_root/AI-dianping-backend"

if [[ "${1:-}" == "--docker" ]]; then
  command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
  docker run --rm \
    -v "$backend:/workspace" \
    -v "${MAVEN_CACHE_DIR:-$HOME/.m2}:/root/.m2" \
    -w /workspace \
    maven:3.9-eclipse-temurin-17 mvn -B -ntp clean verify
else
  (cd "$backend" && mvn -B -ntp clean verify)
fi

echo "PASS: Maven clean verify"
