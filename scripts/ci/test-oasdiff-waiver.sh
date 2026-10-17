#!/usr/bin/env bash
# Checks scripts/ci/oasdiff-breaking.sh in the two situations a waiver
# (<spec>.accepted-breaking.txt) lives through:
#   1. the pull request that adds it: compared with origin/main, the waived
#      breaking changes pass;
#   2. the push to main after it merged: the base already holds the waiver and
#      the spec (simulated with BASE_REF=HEAD), and the check must still pass.
# Needs oasdiff and a fetched origin/main.
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "--- pull request: base origin/main"
BASE_REF=origin/main ./scripts/ci/oasdiff-breaking.sh

echo "--- push to main: the base already holds the waiver and the spec"
BASE_REF=HEAD ./scripts/ci/oasdiff-breaking.sh

echo "oasdiff waiver lifecycle checks passed."
