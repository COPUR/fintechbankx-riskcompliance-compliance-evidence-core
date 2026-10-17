#!/usr/bin/env bash
# Rehearses the monolith -> svc-cmp-evidence compliance report data split end
# to end on a scratch PostgreSQL: builds a monolith-shaped source, applies
# this service's Flyway migrations to a separate database, runs
# db/backfill/run-backfill.sh twice (the second run proves it is idempotent),
# changes a report in the source and runs it again (proving a re-run picks up
# workflow changes made before cut-over), deletes a report in the source and
# runs it again (proving the copy follows deletions and still reconciles),
# and checks the copied values.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
src_db="elms_compliance_backfill_source"
dst_db="cmp_backfill_target"
schema="sc_cmp_evidence"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA $schema"
# Flyway placeholders, filled the way Flyway fills them in the service: the
# runtime role is the connecting role here, so V7 takes its single-user path.
runtime_role="${PGUSER:-$(id -un)}"
# In version order, as Flyway applies them (a plain glob puts V10 before V2).
find "$root/compliance-infrastructure/src/main/resources/db/migration" -name 'V*.sql' | sort -V | while read -r migration; do
  sed "s/\${runtime_role}/$runtime_role/g" "$migration" \
    | PGOPTIONS="-c search_path=$schema" psql_q -d "$dst_db" -f -
done

echo "--- the report currency must be stated, never assumed"
if "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" > /dev/null 2>&1; then
  echo "FAIL a backfill without the report currency ran" >&2
  exit 1
fi
if "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" usd > /dev/null 2>&1; then
  echo "FAIL a backfill with a malformed report currency ran" >&2
  exit 1
fi
echo "ok   report currency required"

for run in 1 2; do
  echo "--- backfill run $run"
  "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD
done

echo "--- monolith approves a report, backfill run 3"
psql_q -d "$src_db" -c "UPDATE compliance_reports SET status = 'APPROVED', reviewed_by = 'officer-2', review_date = '2024-04-12' WHERE report_id = 'CR-2024-Q1-AML'"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD
check "a later workflow change in the monolith is picked up" \
  "SELECT status || ' ' || reviewed_by FROM $schema.legacy_compliance_report WHERE report_id = 'CR-2024-Q1-AML'" "APPROVED officer-2"

echo "--- monolith deletes a report, backfill run 4"
psql_q -d "$src_db" -c "DELETE FROM compliance_reports WHERE report_id = 'CR-2024-Q1-AML'"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD

echo "--- an empty monolith export must not wipe the copy"
psql_q -d "$src_db" -c "ALTER TABLE compliance_reports RENAME TO compliance_reports_hidden" \
  -c "CREATE TABLE compliance_reports (LIKE compliance_reports_hidden INCLUDING ALL)"
if "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db" USD > /dev/null 2>&1; then
  echo "FAIL an empty export was applied" >&2
  exit 1
fi
psql_q -d "$src_db" -c "DROP TABLE compliance_reports" -c "ALTER TABLE compliance_reports_hidden RENAME TO compliance_reports"
echo "ok   empty export refused"


check "reports copied once despite repeated runs; the deleted one removed" \
  "SELECT count(*) FROM $schema.legacy_compliance_report" "2"
check "the report deleted in the monolith is gone from the copy" \
  "SELECT count(*) FROM $schema.legacy_compliance_report WHERE report_id = 'CR-2024-Q1-AML'" "0"
check "amounts carried to the cent" \
  "SELECT sum(total_amount) FROM $schema.legacy_compliance_report" "13350000.50"
check "findings and submission kept" \
  "SELECT findings_details->0->>'rule' || ' ' || regulator_reference FROM $schema.legacy_compliance_report WHERE report_id = 'CR-2024-Q1-FL'" "ECOA-1002.4 CBUAE-FL-2024-0001"
check "report totals carry their currency" \
  "SELECT string_agg(total_amount_currency || ' ' || amount, ',') FROM (SELECT total_amount_currency, sum(total_amount) AS amount FROM $schema.legacy_compliance_report GROUP BY 1 ORDER BY 1) t" "USD 13350000.50"

echo "--- reconciliation compares every copied column"
psql_q -d "$dst_db" -f "$root/db/backfill/01_create_stage_tables.sql"
psql_q -d "$dst_db" -c "INSERT INTO backfill_stage.compliance_reports SELECT report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version FROM $schema.legacy_compliance_report"
for drift in "generated_by = 'someone-else'" "reviewed_by = 'someone-else'" "review_date = DATE '2030-01-01'" "next_report_due = DATE '2030-01-01'" "report_file_path = '/elsewhere.pdf'" "version = version + 1" "updated_at = updated_at + interval '1 second'" "generation_date = generation_date + 1"; do
  found="$( { echo "BEGIN;"; echo "UPDATE $schema.legacy_compliance_report SET $drift WHERE report_id = 'CR-2024-Q1-FL';"; cat "$root/db/backfill/03_reconcile.sql"; echo "ROLLBACK;"; } \
    | psql -X -q -At -v ON_ERROR_STOP=1 -d "$dst_db" -f - | grep -v '^totals|' || true)"
  if [ "$found" != "CR-2024-Q1-FL" ]; then
    echo "FAIL reconciliation missed drift in: $drift (got '$found')" >&2
    exit 1
  fi
done
psql_q -d "$dst_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "ok   reconciliation reports drift in any copied column"

check "screening evidence untouched by the report backfill" \
  "SELECT count(*) FROM $schema.compliance_screening" "0"

echo "Backfill rehearsal passed."
