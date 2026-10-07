-- Fixture with the column layout and checks of the monolith's
-- V13__Create_compliance_reports_table.sql (indexes and updated_at trigger
-- left out). Used by scripts/migration/verify-backfill.sh.
CREATE TABLE compliance_reports (
    report_id VARCHAR(30) PRIMARY KEY,
    report_type VARCHAR(50) NOT NULL CHECK (report_type IN ('FAIR_LENDING', 'RISK_ASSESSMENT', 'CFPB_EXAMINATION', 'HMDA_REPORTING', 'CRA_ASSESSMENT', 'BSA_AML', 'FDCPA_COMPLIANCE')),
    generation_date DATE NOT NULL,
    reporting_period_start DATE NOT NULL,
    reporting_period_end DATE NOT NULL,
    total_loans INTEGER NOT NULL CHECK (total_loans >= 0),
    total_amount DECIMAL(20,2) NOT NULL CHECK (total_amount >= 0),
    high_risk_loans INTEGER DEFAULT 0 CHECK (high_risk_loans >= 0),
    compliance_score DECIMAL(5,2) CHECK (compliance_score >= 0 AND compliance_score <= 100),
    regulatory_findings INTEGER DEFAULT 0 CHECK (regulatory_findings >= 0),
    findings_details JSONB,
    report_data JSONB,
    report_file_path VARCHAR(500),
    generated_by VARCHAR(100) NOT NULL,
    reviewed_by VARCHAR(100),
    review_date DATE,
    status VARCHAR(30) DEFAULT 'GENERATED' CHECK (status IN ('GENERATED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'SUBMITTED')),
    submission_date DATE,
    regulator_reference VARCHAR(50),
    next_report_due DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0,
    
    -- Business rule constraints
    CONSTRAINT chk_reporting_period_valid CHECK (reporting_period_end >= reporting_period_start),
    CONSTRAINT chk_generation_date_valid CHECK (generation_date >= reporting_period_end),
    CONSTRAINT chk_high_risk_not_exceed_total CHECK (high_risk_loans <= total_loans),
    CONSTRAINT chk_review_date_after_generation CHECK (review_date IS NULL OR review_date >= generation_date),
    CONSTRAINT chk_submission_after_approval CHECK (submission_date IS NULL OR status = 'SUBMITTED')
);

INSERT INTO compliance_reports (report_id, report_type, generation_date, reporting_period_start, reporting_period_end,
    total_loans, total_amount, high_risk_loans, compliance_score, regulatory_findings, findings_details, report_data,
    generated_by, reviewed_by, review_date, status, submission_date, regulator_reference) VALUES
  ('CR-2024-Q1-FL', 'FAIR_LENDING', '2024-04-05', '2024-01-01', '2024-03-31', 120, 6250000.00, 9, 96.50, 1,
   '[{"rule": "ECOA-1002.4", "severity": "LOW"}]', '{"approvalRateGap": 0.012}',
   'compliance-batch', 'officer-1', '2024-04-10', 'SUBMITTED', '2024-04-15', 'CBUAE-FL-2024-0001'),
  ('CR-2024-Q1-AML', 'BSA_AML', '2024-04-06', '2024-01-01', '2024-03-31', 120, 6250000.00, 4, 99.00, 0,
   NULL, '{"sarsFiled": 2}', 'compliance-batch', NULL, NULL, 'UNDER_REVIEW', NULL, NULL),
  ('CR-2024-Q2-FL', 'FAIR_LENDING', '2024-07-03', '2024-04-01', '2024-06-30', 140, 7100000.50, 11, 92.25, 3,
   '[{"rule": "ECOA-1002.6", "severity": "MEDIUM"}]', '{"approvalRateGap": 0.021}',
   'compliance-batch', NULL, NULL, 'GENERATED', NULL, NULL);
