-- Step 2 of the compliance report data split: copy the staged monolith rows
-- into sc_cmp_evidence.legacy_compliance_report. Run by run-backfill.sh
-- against the COMPLIANCE SERVICE database (db_cmp_evidence_<env>) after Flyway.
--
-- Every column is copied as is. Reports still move through a review and
-- submission workflow in the monolith until cut-over, so a re-run refreshes
-- rows already copied instead of skipping them; nothing in this service
-- writes these rows, so the monolith stays the source of truth until then.

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO sc_cmp_evidence.legacy_compliance_report (
    report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version)
SELECT report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version
  FROM backfill_stage.compliance_reports
ON CONFLICT (report_id) DO UPDATE SET
       report_type = EXCLUDED.report_type,
       generation_date = EXCLUDED.generation_date,
       reporting_period_start = EXCLUDED.reporting_period_start,
       reporting_period_end = EXCLUDED.reporting_period_end,
       total_loans = EXCLUDED.total_loans,
       total_amount = EXCLUDED.total_amount,
       high_risk_loans = EXCLUDED.high_risk_loans,
       compliance_score = EXCLUDED.compliance_score,
       regulatory_findings = EXCLUDED.regulatory_findings,
       findings_details = EXCLUDED.findings_details,
       report_data = EXCLUDED.report_data,
       report_file_path = EXCLUDED.report_file_path,
       generated_by = EXCLUDED.generated_by,
       reviewed_by = EXCLUDED.reviewed_by,
       review_date = EXCLUDED.review_date,
       status = EXCLUDED.status,
       submission_date = EXCLUDED.submission_date,
       regulator_reference = EXCLUDED.regulator_reference,
       next_report_due = EXCLUDED.next_report_due,
       created_at = EXCLUDED.created_at,
       updated_at = EXCLUDED.updated_at,
       version = EXCLUDED.version;

COMMIT;
