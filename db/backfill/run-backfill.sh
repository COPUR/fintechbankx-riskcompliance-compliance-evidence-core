#!/usr/bin/env bash
# Copies regulatory compliance reports from the monolith database into
# svc-cmp-evidence's own database and reconciles the two. Re-runnable: a
# re-run refreshes reports the monolith changed since the last run.
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <compliance-service-conninfo>
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <monolith-conninfo> <compliance-service-conninfo>" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

echo "Exporting compliance reports from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version FROM compliance_reports ORDER BY report_id) TO '$work/compliance_reports.csv' WITH (FORMAT csv, HEADER true)
COMMIT;
SQL

echo "Staging and transforming into sc_cmp_evidence..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.compliance_reports FROM '$work/compliance_reports.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -f "$here/02_transform_into_compliance_service.sql"

echo "Reconciling..."
source_figures="$(psql -X -At "$source_db" -c "
  SELECT count(*), coalesce(sum(total_loans), 0), coalesce(sum(total_amount), 0)::numeric(22,2), coalesce(sum(regulatory_findings), 0) FROM compliance_reports")"
reconcile="$(psql -X -At "$target_db" -f "$here/03_reconcile.sql")"
target_figures="$(echo "$reconcile" | sed -n '1p')"
mismatches="$(echo "$reconcile" | sed -n '2,$p')"

echo "monolith           reports|loans|amount|findings: $source_figures"
echo "compliance service reports|loans|amount|findings: $target_figures"
if [ "$source_figures" != "$target_figures" ]; then
  echo "RECONCILIATION FAILED: totals differ" >&2
  exit 1
fi
if [ -n "$mismatches" ]; then
  echo "RECONCILIATION FAILED: reports whose copy differs from the monolith:" >&2
  echo "$mismatches" >&2
  exit 1
fi

run "$target_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "Backfill reconciled."
