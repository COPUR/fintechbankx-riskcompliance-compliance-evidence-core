-- The monolith's compliance_reports.total_amount has no currency. The backfill
-- now records the currency the operator states for the monolith's reports
-- (run-backfill.sh third argument; no default while the home-currency
-- decision is open), and reconciliation sums total_amount per currency.
-- NOT VALID: rows copied before this migration get the currency on the next
-- backfill run, which rewrites every row.

ALTER TABLE legacy_compliance_report ADD COLUMN total_amount_currency CHAR(3);

ALTER TABLE legacy_compliance_report ADD CONSTRAINT ck_legacy_compliance_report_currency
    CHECK (total_amount_currency IS NOT NULL AND total_amount_currency ~ '^[A-Z]{3}$') NOT VALID;

COMMENT ON COLUMN legacy_compliance_report.total_amount_currency IS
    'ISO 4217 currency of total_amount, stated by the operator at backfill (the monolith table has none).';
