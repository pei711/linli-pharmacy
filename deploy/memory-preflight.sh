#!/bin/sh
# Run before starting/recreating services. Enforces both the aggregate Compose
# hard-cap budget and the live host-use ceiling.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_file=${1:-$script_dir/server-test.compose.yml}
container_budget=$(awk -F= '$1 == "CONTAINER_MEMORY_BUDGET_MIB" { print $2 }' "$script_dir/memory-budget.conf")
case "$container_budget" in ''|*[!0-9]*) echo "Invalid container memory budget" >&2; exit 2;; esac

configured_mib=$(awk '
  /^[[:space:]]*mem_limit:[[:space:]]*/ {
    value = $0
    sub(/^[[:space:]]*mem_limit:[[:space:]]*/, "", value)
    unit = substr(value, length(value), 1)
    amount = substr(value, 1, length(value) - 1) + 0
    if (unit == "m" || unit == "M") total += amount
    else if (unit == "g" || unit == "G") total += amount * 1024
    else { print "Unsupported mem_limit value: " value > "/dev/stderr"; exit 2 }
  }
  END { if (NR > 0) print total + 0 }
' "$compose_file")
if [ "$configured_mib" -gt "$container_budget" ]; then
  echo "FAIL: Compose memory caps total ${configured_mib} MiB; budget is ${container_budget} MiB" >&2
  exit 1
fi
echo "PASS: Compose hard caps ${configured_mib}/${container_budget} MiB"
"$script_dir/check-host-memory.sh"
