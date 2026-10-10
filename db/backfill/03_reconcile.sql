-- Step 3 of the compliance report data split, run by run-backfill.sh against
-- the COMPLIANCE SERVICE database. Rows starting with "totals|" hold the totals
-- per currency, compared with the monolith's (read in the export snapshot);
-- any other row is a staged report whose copy differs from it in any copied
-- column, or a copied report that no longer exists in the monolith.

\set ON_ERROR_STOP on

SELECT 'totals',
       total_amount_currency,
       count(*)                                      AS reports,
       coalesce(sum(total_loans), 0)                 AS loans_total,
       coalesce(sum(total_amount), 0)::numeric(22,2) AS amount_total,
       coalesce(sum(regulatory_findings), 0)         AS findings_total
  FROM sc_cmp_evidence.legacy_compliance_report
 GROUP BY total_amount_currency
 ORDER BY total_amount_currency;

-- Every column the backfill copies (all 23 of the monolith table) is compared.
SELECT s.report_id
  FROM backfill_stage.compliance_reports s
  LEFT JOIN sc_cmp_evidence.legacy_compliance_report t USING (report_id)
 WHERE t.report_id IS NULL
    OR (s.report_type, s.generation_date, s.reporting_period_start, s.reporting_period_end, s.total_loans,
        s.total_amount, s.high_risk_loans, s.compliance_score, s.regulatory_findings, s.findings_details,
        s.report_data, s.report_file_path, s.generated_by, s.reviewed_by, s.review_date, s.status,
        s.submission_date, s.regulator_reference, s.next_report_due, s.created_at, s.updated_at, s.version)
       IS DISTINCT FROM
       (t.report_type, t.generation_date, t.reporting_period_start, t.reporting_period_end, t.total_loans,
        t.total_amount, t.high_risk_loans, t.compliance_score, t.regulatory_findings, t.findings_details,
        t.report_data, t.report_file_path, t.generated_by, t.reviewed_by, t.review_date, t.status,
        t.submission_date, t.regulator_reference, t.next_report_due, t.created_at, t.updated_at, t.version)
UNION ALL
SELECT t.report_id || ' (not in monolith)'
  FROM sc_cmp_evidence.legacy_compliance_report t
  LEFT JOIN backfill_stage.compliance_reports s USING (report_id)
 WHERE s.report_id IS NULL
 ORDER BY 1;
