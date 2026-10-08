#!/usr/bin/env bash
# Guardrail: no generic Map<String,Object> payloads in controllers.
# Usage: check-generic-maps.sh [repository root]
set -euo pipefail
root="${1:-.}"
cd "$root"
if [ -d src/main/java ]; then
  if grep -R -nE "Map<\s*String\s*,\s*Object\s*>" src/main/java --include="*Controller.java"; then
    echo "Generic Map<String,Object> payloads are disallowed in controllers."
    exit 1
  fi
else
  echo "No Java controller sources in this repository; guardrail check skipped."
fi
