#!/usr/bin/env bash
# Rehearses the monolith -> svc-cmp-evidence compliance report data split end
# to end on a scratch PostgreSQL: builds a monolith-shaped source, applies
# this service's Flyway migrations to a separate database, runs
# db/backfill/run-backfill.sh twice (the second run proves it is idempotent),
# changes a report in the source and runs it again (proving a re-run picks up
# workflow changes made before cut-over), and checks the copied values.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
src_db="elms_compliance_backfill_source"
dst_db="cmp_backfill_target"
schema="sc_cmp_evidence"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA $schema"
for migration in "$root"/compliance-infrastructure/src/main/resources/db/migration/V*.sql; do
  PGOPTIONS="-c search_path=$schema" psql_q -d "$dst_db" -f "$migration"
done

for run in 1 2; do
  echo "--- backfill run $run"
  "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db"
done

echo "--- monolith approves a report, backfill run 3"
psql_q -d "$src_db" -c "UPDATE compliance_reports SET status = 'APPROVED', reviewed_by = 'officer-2', review_date = '2024-04-12' WHERE report_id = 'CR-2024-Q1-AML'"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db"

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

check "reports copied once despite three runs" \
  "SELECT count(*) FROM $schema.legacy_compliance_report" "3"
check "amounts carried to the cent" \
  "SELECT sum(total_amount) FROM $schema.legacy_compliance_report" "19600000.50"
check "findings and submission kept" \
  "SELECT findings_details->0->>'rule' || ' ' || regulator_reference FROM $schema.legacy_compliance_report WHERE report_id = 'CR-2024-Q1-FL'" "ECOA-1002.4 CBUAE-FL-2024-0001"
check "a later workflow change in the monolith is picked up" \
  "SELECT status || ' ' || reviewed_by FROM $schema.legacy_compliance_report WHERE report_id = 'CR-2024-Q1-AML'" "APPROVED officer-2"
check "screening evidence untouched by the report backfill" \
  "SELECT count(*) FROM $schema.compliance_screening" "0"

echo "Backfill rehearsal passed."
