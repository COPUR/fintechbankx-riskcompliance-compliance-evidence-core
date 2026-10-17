-- Step 2 of the compliance report data split: copy the staged monolith rows
-- into sc_cmp_evidence.legacy_compliance_report. Run by run-backfill.sh
-- against the COMPLIANCE SERVICE database (db_cmp_evidence_<env>) after Flyway.
--
-- Every column is copied as is; total_amount_currency is the currency the
-- operator states for the monolith's amounts (psql variable report_currency,
-- run-backfill.sh third argument). Reports still move through a review and
-- submission workflow in the monolith until cut-over, so a re-run refreshes
-- rows already copied instead of skipping them; nothing in this service
-- writes these rows, so the monolith stays the source of truth until then.
--
-- legacy_compliance_report is a mirror of the monolith table until cut-over,
-- so a report deleted in the monolith is deleted from the copy too (this
-- touches only legacy_compliance_report, never compliance_screening). An
-- empty export against a non-empty copy is refused instead of wiping it: it
-- almost always means the wrong source database.

\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM backfill_stage.compliance_reports)
       AND EXISTS (SELECT 1 FROM sc_cmp_evidence.legacy_compliance_report) THEN
        RAISE EXCEPTION 'monolith export is empty but legacy_compliance_report has rows; refusing to delete them';
    END IF;
END
$$;

DELETE FROM sc_cmp_evidence.legacy_compliance_report t
 WHERE NOT EXISTS (SELECT 1 FROM backfill_stage.compliance_reports s WHERE s.report_id = t.report_id);

INSERT INTO sc_cmp_evidence.legacy_compliance_report (
    report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version, total_amount_currency)
SELECT report_id, report_type, generation_date, reporting_period_start, reporting_period_end, total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data, report_file_path, generated_by, reviewed_by, review_date, status, submission_date, regulator_reference, next_report_due, created_at, updated_at, version, :'report_currency'
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
       version = EXCLUDED.version,
       total_amount_currency = EXCLUDED.total_amount_currency;

COMMIT;
