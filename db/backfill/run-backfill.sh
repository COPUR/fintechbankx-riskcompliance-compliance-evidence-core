#!/usr/bin/env bash
# Copies regulatory compliance reports from the monolith database into
# svc-cmp-evidence's own database and reconciles the two. Re-runnable: a
# re-run refreshes reports the monolith changed since the last run and
# removes copies of reports the monolith deleted.
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <compliance-service-conninfo> <report-currency>
#
# <report-currency> is the ISO 4217 code of the monolith's report amounts
# (compliance_reports.total_amount has no currency column). It is required:
# the home-currency decision is open, so the run never assumes one.
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <monolith-conninfo> <compliance-service-conninfo> <report-currency>" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
report_currency="$3"
if ! [[ "$report_currency" =~ ^[A-Z]{3}$ ]]; then
  echo "report currency must be an upper-case ISO 4217 code, got '$report_currency'" >&2
  exit 2
fi
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

echo "Exporting compliance reports and their totals from the monolith (one read-only snapshot)..."
# The totals are read in the same REPEATABLE READ transaction as the copy, so
# a report the monolith writes meanwhile cannot make them disagree.
source_figures="$(psql -X -q -At -v ON_ERROR_STOP=1 "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version FROM compliance_reports ORDER BY report_id) TO '$work/compliance_reports.csv' WITH (FORMAT csv, HEADER true)
SELECT 'totals', '$report_currency', count(*), coalesce(sum(total_loans), 0), coalesce(sum(total_amount), 0)::numeric(22,2), coalesce(sum(regulatory_findings), 0)
  FROM compliance_reports HAVING count(*) > 0;
COMMIT;
SQL
)"

echo "Staging and transforming into sc_cmp_evidence..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.compliance_reports FROM '$work/compliance_reports.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -v report_currency="$report_currency" -f "$here/02_transform_into_compliance_service.sql"

echo "Reconciling..."
reconcile="$(psql -X -At -v ON_ERROR_STOP=1 "$target_db" -f "$here/03_reconcile.sql")"
target_figures="$(grep '^totals|' <<<"$reconcile" || true)"
mismatches="$(grep -v '^totals|' <<<"$reconcile" || true)"

echo "monolith           totals|currency|reports|loans|amount|findings: ${source_figures:-none}"
echo "compliance service totals|currency|reports|loans|amount|findings: ${target_figures:-none}"
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
