-- Step 3 of the compliance report data split, run by run-backfill.sh against
-- the COMPLIANCE SERVICE database. Row 1 holds totals compared with the
-- monolith; any further row is a staged report whose copy differs from it.

\set ON_ERROR_STOP on

SELECT count(*)                                      AS reports,
       coalesce(sum(total_loans), 0)                 AS loans_total,
       coalesce(sum(total_amount), 0)::numeric(22,2) AS amount_total,
       coalesce(sum(regulatory_findings), 0)         AS findings_total
  FROM sc_cmp_evidence.legacy_compliance_report;

SELECT s.report_id
  FROM backfill_stage.compliance_reports s
  LEFT JOIN sc_cmp_evidence.legacy_compliance_report t USING (report_id)
 WHERE t.report_id IS NULL
    OR (s.report_type, s.status, s.total_loans, s.total_amount, s.high_risk_loans, s.compliance_score,
        s.regulatory_findings, s.findings_details, s.report_data, s.submission_date, s.regulator_reference)
       IS DISTINCT FROM
       (t.report_type, t.status, t.total_loans, t.total_amount, t.high_risk_loans, t.compliance_score,
        t.regulatory_findings, t.findings_details, t.report_data, t.submission_date, t.regulator_reference)
 ORDER BY s.report_id;
