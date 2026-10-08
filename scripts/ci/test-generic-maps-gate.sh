#!/usr/bin/env bash
# Checks scripts/ci/check-generic-maps.sh: it passes on this repository and
# fails when a Map<String,Object> is planted in a module's controller or DTO.
set -euo pipefail
cd "$(dirname "$0")/../.."
gate="$PWD/scripts/ci/check-generic-maps.sh"

echo "--- this repository"
"$gate" .

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
web=compliance-infrastructure/src/main/java/com/bank/compliance/infrastructure/web

plant() {
  local file="$1" label="$2"
  rm -rf "$work/repo" && mkdir -p "$work/repo"
  for module in */src/main/java; do
    mkdir -p "$work/repo/$module" && cp -R "$module/." "$work/repo/$module/"
  done
  printf '\nclass Planted { java.util.Map<String, Object> body; }\n' >> "$work/repo/$file"
  if "$gate" "$work/repo" > /dev/null 2>&1; then
    echo "FAIL the gate accepted a Map<String,Object> planted in $label ($file)" >&2
    exit 1
  fi
  echo "ok: planted case in $label is rejected"
}

echo "--- planted cases"
plant "$web/ComplianceController.java" "a module controller"
plant "$web/dto/ComplianceScreeningRequest.java" "a module request DTO"
echo "generic map gate checks passed."
