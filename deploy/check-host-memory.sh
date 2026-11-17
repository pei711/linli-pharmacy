#!/bin/sh
# Fail closed when the host's available memory budget is reached.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
limit_mib=$(awk -F= '$1 == "HOST_MEMORY_LIMIT_MIB" { print $2 }' "$script_dir/memory-budget.conf")
case "$limit_mib" in ''|*[!0-9]*) echo "Invalid host memory limit" >&2; exit 2;; esac
total_kib=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)
available_kib=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
used_mib=$(((total_kib - available_kib) / 1024))
total_mib=$((total_kib / 1024))
if [ "$used_mib" -ge "$limit_mib" ]; then
  echo "FAIL: host memory budget reached (${used_mib}/${limit_mib} MiB used; ${total_mib} MiB total)" >&2
  exit 1
fi
echo "PASS: host memory ${used_mib}/${limit_mib} MiB used (${total_mib} MiB total)"
