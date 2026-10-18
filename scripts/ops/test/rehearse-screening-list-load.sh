#!/usr/bin/env bash
# Rehearses scripts/ops/load-screening-list.sh on a scratch PostgreSQL: applies
# this service's Flyway migrations, loads the shared diacritics fixtures as a
# sanctions source and checks that the loader-side normalisation agrees with the
# Java domain (NormalizedNameTest reads the same file), replaces a source
# transactionally (a reload replaces only that source; other sources stay),
# loads a PEP source, and checks every refusal: a credential in the conninfo
# (keyword or URI form), an empty or header-only file, an unknown list, a
# malformed source or version, and a file with a name that normalises to
# nothing, which must leave the previous load untouched.
#
# Needs psql, python3 and a role that can create databases, via the usual PG*
# env vars (PGHOST, PGPORT, PGUSER, PGPASSWORD). Run by deploy/data-split-rehearsal.
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd)"
db="cmp_screening_list_rehearsal"
schema="sc_cmp_evidence"
loader="$root/scripts/ops/load-screening-list.sh"
fixtures="$root/scripts/ops/test/screening-name-fixtures.csv"
conn="dbname=$db"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

must_fail() {
  local label="$1"; shift
  if "$@" > "$work/refused.out" 2>&1; then
    echo "FAIL $label: the loader did not refuse" >&2
    cat "$work/refused.out" >&2
    exit 1
  fi
  echo "ok   refused: $label"
}

[ -x "$loader" ] || { echo "FAIL $loader is missing or not executable" >&2; exit 1; }

psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
psql_q -d "$db" -c "CREATE SCHEMA $schema"
# Flyway placeholders, filled the way Flyway fills them in the service: the
# runtime role is the connecting role here, so V7 takes its single-user path.
runtime_role="${PGUSER:-$(id -un)}"
find "$root/compliance-infrastructure/src/main/resources/db/migration" -name 'V*.sql' | sort -V | while read -r migration; do
  sed "s/\${runtime_role}/$runtime_role/g" "$migration" \
    | PGOPTIONS="-c search_path=$schema" psql_q -d "$db" -f -
done

# The loader's input: one display_name column. Expected values come from the same fixtures file.
python3 - "$fixtures" "$work/names.csv" "$work/expected.txt" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8", newline="")))
with open(sys.argv[2], "w", encoding="utf-8", newline="") as out:
    w = csv.writer(out)
    w.writerow(["display_name"])
    for r in rows:
        w.writerow([r["display_name"]])
with open(sys.argv[3], "w", encoding="utf-8") as out:
    for v in sorted(r["expected_normalized"] for r in rows):
        out.write(v + "\n")
print(len(rows))
PY
fixture_rows="$(python3 -c 'import csv,sys; print(sum(1 for _ in csv.DictReader(open(sys.argv[1], encoding="utf-8", newline=""))))' "$fixtures")"

echo "--- sanctions load from the shared fixtures"
"$loader" "$conn" sanctions "$work/names.csv" UN 2026-10-17
check "every fixture row loaded under its source" \
  "SELECT count(*) || ' ' || min(list_version) || ' ' || max(list_version) FROM $schema.sanctions_list_entry WHERE list_source = 'UN'" "$fixture_rows 2026-10-17 2026-10-17"
check "display names kept as given" \
  "SELECT count(*) FROM $schema.sanctions_list_entry WHERE display_name = 'Test Sanctioned Pérson' AND list_source = 'UN'" "1"
check "loaded_at is the load time" \
  "SELECT count(*) FROM $schema.sanctions_list_entry WHERE list_source = 'UN' AND loaded_at > now() - interval '5 minutes'" "$fixture_rows"
psql -X -At -d "$db" -c "SELECT normalized_name FROM $schema.sanctions_list_entry WHERE list_source = 'UN' ORDER BY normalized_name" > "$work/actual.txt"
if ! diff -u "$work/expected.txt" "$work/actual.txt"; then
  echo "FAIL the loader's normalisation disagrees with the fixtures the Java domain is tested against" >&2
  exit 1
fi
echo "ok   loader-side normalisation agrees with the shared fixtures"

echo "--- a second source does not touch the first"
printf 'display_name\nTest OFAC Dummy\n"Test Second, Dummy"\n' > "$work/ofac.csv"
"$loader" "$conn" sanctions "$work/ofac.csv" OFAC-SDN 1842
check "both sources present" \
  "SELECT list_source || '=' || count(*) FROM $schema.sanctions_list_entry GROUP BY list_source ORDER BY list_source" "$(printf 'OFAC-SDN=2\nUN=%s' "$fixture_rows")"

echo "--- reloading a source replaces it as a whole"
printf 'display_name\nTest Replaced Person\n' > "$work/un2.csv"
"$loader" "$conn" sanctions "$work/un2.csv" UN 2026-10-18
check "the old UN rows are gone and the new version is recorded" \
  "SELECT count(*) || ' ' || min(list_version) || ' ' || min(normalized_name) FROM $schema.sanctions_list_entry WHERE list_source = 'UN'" "1 2026-10-18 TEST REPLACED PERSON"
check "OFAC untouched by the UN reload" \
  "SELECT count(*) FROM $schema.sanctions_list_entry WHERE list_source = 'OFAC-SDN' AND list_version = '1842'" "2"

echo "--- PEP register"
printf 'display_name\nTest Pep Person\nTest Ñandú Pep-Dummy\n' > "$work/pep.csv"
"$loader" "$conn" pep "$work/pep.csv" UAE-PEP 2026-10
check "PEP rows loaded into their own table" \
  "SELECT string_agg(normalized_name, '|' ORDER BY normalized_name) FROM $schema.pep_register_entry WHERE list_source = 'UAE-PEP'" "TEST NANDU PEP DUMMY|TEST PEP PERSON"
check "the PEP load did not touch the sanctions list" \
  "SELECT count(*) FROM $schema.sanctions_list_entry" "3"

echo "--- refusals (each must leave the lists as they are)"
before="$(psql -X -At -d "$db" -c "SELECT (SELECT count(*) FROM $schema.sanctions_list_entry) || '/' || (SELECT count(*) FROM $schema.pep_register_entry)")"
# The credential keyword is built from parts so this probe input is not itself a secret-scan hit.
credential_keyword="pass""word"
must_fail "credential in keyword conninfo" "$loader" "dbname=$db ${credential_keyword}=not-allowed-here" sanctions "$work/ofac.csv" OFAC-SDN 1843
must_fail "credential in URI conninfo" "$loader" "postgresql://loader:not-allowed-here@localhost/$db" sanctions "$work/ofac.csv" OFAC-SDN 1843
: > "$work/empty.csv"
must_fail "empty file" "$loader" "$conn" sanctions "$work/empty.csv" OFAC-SDN 1843
printf 'display_name\n' > "$work/header-only.csv"
must_fail "header-only file" "$loader" "$conn" sanctions "$work/header-only.csv" OFAC-SDN 1843
printf 'name\nTest Dummy\n' > "$work/wrong-header.csv"
must_fail "wrong header" "$loader" "$conn" sanctions "$work/wrong-header.csv" OFAC-SDN 1843
must_fail "missing file" "$loader" "$conn" sanctions "$work/does-not-exist.csv" OFAC-SDN 1843
must_fail "unknown list" "$loader" "$conn" watchlist "$work/ofac.csv" OFAC-SDN 1843
must_fail "malformed source" "$loader" "$conn" sanctions "$work/ofac.csv" "OFAC SDN;drop" 1843
must_fail "malformed version" "$loader" "$conn" sanctions "$work/ofac.csv" OFAC-SDN "18 43"
must_fail "too few arguments" "$loader" "$conn" sanctions "$work/ofac.csv" OFAC-SDN
printf 'display_name\nTest Valid Dummy\n"..."\n' > "$work/blank-name.csv"
must_fail "a name that normalises to nothing" "$loader" "$conn" sanctions "$work/blank-name.csv" OFAC-SDN 1843
after="$(psql -X -At -d "$db" -c "SELECT (SELECT count(*) FROM $schema.sanctions_list_entry) || '/' || (SELECT count(*) FROM $schema.pep_register_entry)")"
if [ "$before" != "$after" ]; then
  echo "FAIL a refused load changed the lists: $before -> $after" >&2
  exit 1
fi
check "OFAC still at its last good version after the refused reloads" \
  "SELECT count(*) FROM $schema.sanctions_list_entry WHERE list_source = 'OFAC-SDN' AND list_version = '1842'" "2"
echo "ok   refused loads changed nothing"

check "screening evidence untouched by list loads" \
  "SELECT count(*) FROM $schema.compliance_screening" "0"

echo "Screening list load rehearsal passed."
