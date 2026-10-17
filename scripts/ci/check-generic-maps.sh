#!/usr/bin/env bash
# Guardrail: no generic Map<String,Object> payloads in controllers or DTOs.
# Scans the root src/main/java and every module's <module>/src/main/java:
# *Controller.java, *Request.java, *Response.java, *Dto.java and any file in
# a web or dto package.
# Usage: check-generic-maps.sh [repository root]
set -euo pipefail
root="${1:-.}"
cd "$root"

mapfile -t sources < <(find . -path '*/build/*' -prune -o -path '*/src/main/java/*' -type f -name '*.java' \
  \( -name '*Controller.java' -o -name '*Request.java' -o -name '*Response.java' -o -name '*Dto.java' \
     -o -path '*/web/*' -o -path '*/dto/*' \) -print | sort)

if [ "${#sources[@]}" -eq 0 ]; then
  echo "No Java controller or DTO sources in this repository; guardrail check skipped."
  exit 0
fi

if grep -nE "Map<\s*String\s*,\s*Object\s*>" "${sources[@]}"; then
  echo "Generic Map<String,Object> payloads are disallowed in controllers and DTOs; use typed records." >&2
  exit 1
fi
echo "No generic Map<String,Object> in ${#sources[@]} controller/DTO sources."
