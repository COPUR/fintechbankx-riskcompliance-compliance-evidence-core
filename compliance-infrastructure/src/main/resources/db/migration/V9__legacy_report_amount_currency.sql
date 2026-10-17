-- The monolith's compliance_reports.total_amount has no currency. The backfill
-- records the currency the operator states for the monolith's reports
-- (run-backfill.sh third argument; no default while the home-currency
-- decision is open), and reconciliation sums total_amount per currency.
-- Mandatory and validated from the start: this schema has not been released
-- to any environment, and the backfill always writes the currency.

ALTER TABLE legacy_compliance_report ADD COLUMN total_amount_currency CHAR(3) NOT NULL;

ALTER TABLE legacy_compliance_report ADD CONSTRAINT ck_legacy_compliance_report_currency
    CHECK (total_amount_currency ~ '^[A-Z]{3}$');

COMMENT ON COLUMN legacy_compliance_report.total_amount_currency IS
    'ISO 4217 currency of total_amount, stated by the operator at backfill (the monolith table has none).';
