#!/usr/bin/env bash
# Provider AsyncAPI breaking-change gate (ADR-019 section 5, adr-runbooks 2e7cf13).
#
# asyncapi-breaking.mjs and lib/asyncapi-model.mjs are copied UNCHANGED from
# fintechbankx-governance-api-contracts-asyncapi-catalog at a7b9b9d until
# platform adds the gate to the shared CI template. That script reads
# asyncapi/*.yaml at the repository root; this service keeps its contract in
# api/asyncapi/. So this wrapper builds a throwaway git repository with two
# commits, the merge base's api/asyncapi as asyncapi/ and the working tree's
# api/asyncapi on top, and runs the unchanged script there.
#
# Usage: BASE_REF=origin/main scripts/ci/asyncapi/run-breaking.sh
# Needs full history (fetch-depth 0) and `npm ci` in scripts/ci/asyncapi.
set -euo pipefail
repo="$(git rev-parse --show-toplevel)"
here="$repo/scripts/ci/asyncapi"
base_ref="${BASE_REF:-origin/main}"

if ! git -C "$repo" rev-parse --verify --quiet "${base_ref}^{commit}" > /dev/null; then
  echo "BASE_REF $base_ref is not available. Fetch full history (actions/checkout fetch-depth: 0) or set BASE_REF." >&2
  exit 2
fi
base="$(git -C "$repo" merge-base "$base_ref" HEAD 2>/dev/null || git -C "$repo" rev-parse "$base_ref")"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
git -C "$work" init -q
git -C "$work" config user.name asyncapi-gate
git -C "$work" config user.email asyncapi-gate@invalid
git -C "$work" config commit.gpgsign false

# Commit 1: the base's api/asyncapi as asyncapi/ (empty when the base has none).
mkdir -p "$work/asyncapi"
if git -C "$repo" cat-file -e "$base:api/asyncapi" 2> /dev/null; then
  git -C "$repo" archive "$base" api/asyncapi | tar -x -C "$work" --strip-components=1
fi
git -C "$work" add -A
git -C "$work" commit -q --allow-empty -m "base ${base:0:12}"
git -C "$work" branch -q base

# Commit 2: the working tree's api/asyncapi.
find "$work/asyncapi" -mindepth 1 -delete
cp -R "$repo/api/asyncapi/." "$work/asyncapi/"
git -C "$work" add -A
git -C "$work" commit -q --allow-empty -m head

echo "asyncapi breaking gate: api/asyncapi against $base_ref (merge base ${base:0:12})"
cd "$work"
BASE_REF=base node "$here/asyncapi-breaking.mjs"
