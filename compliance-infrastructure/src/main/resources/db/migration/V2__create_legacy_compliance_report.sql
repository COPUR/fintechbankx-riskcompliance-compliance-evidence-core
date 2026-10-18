-- Regulatory compliance reports, moved from the monolith's
-- V13__Create_compliance_reports_table.sql (public.compliance_reports) by
-- db/backfill/run-backfill.sh. Kept as regulatory evidence; nothing in this
-- service writes them yet. Differences from the monolith table:
--   * no updated_at trigger: rows are copied as stored, timestamps included;
--   * the monolith had no foreign keys, so none are dropped here.

CREATE TABLE legacy_compliance_report (
    report_id               VARCHAR(30)    PRIMARY KEY,
    report_type             VARCHAR(50)    NOT NULL,
    generation_date         DATE           NOT NULL,
    reporting_period_start  DATE           NOT NULL,
    reporting_period_end    DATE           NOT NULL,
    total_loans             INTEGER        NOT NULL,
    total_amount            NUMERIC(20, 2) NOT NULL,
    high_risk_loans         INTEGER        DEFAULT 0,
    compliance_score        NUMERIC(5, 2),
    regulatory_findings     INTEGER        DEFAULT 0,
    findings_details        JSONB,
    report_data             JSONB,
    report_file_path        VARCHAR(500),
    generated_by            VARCHAR(100)   NOT NULL,
    reviewed_by             VARCHAR(100),
    review_date             DATE,
    status                  VARCHAR(30)    DEFAULT 'GENERATED',
    submission_date         DATE,
    regulator_reference     VARCHAR(50),
    next_report_due         DATE,
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ,
    version                 INTEGER        DEFAULT 0,

    CONSTRAINT ck_legacy_compliance_report_type CHECK (report_type IN ('FAIR_LENDING', 'RISK_ASSESSMENT',
        'CFPB_EXAMINATION', 'HMDA_REPORTING', 'CRA_ASSESSMENT', 'BSA_AML', 'FDCPA_COMPLIANCE')),
    CONSTRAINT ck_legacy_compliance_report_status CHECK (status IN ('GENERATED', 'UNDER_REVIEW', 'APPROVED',
        'REJECTED', 'SUBMITTED')),
    CONSTRAINT ck_legacy_compliance_report_counts CHECK (total_loans >= 0 AND high_risk_loans >= 0
        AND regulatory_findings >= 0 AND high_risk_loans <= total_loans),
    CONSTRAINT ck_legacy_compliance_report_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_legacy_compliance_report_score CHECK (compliance_score BETWEEN 0 AND 100),
    CONSTRAINT ck_legacy_compliance_report_period CHECK (reporting_period_end >= reporting_period_start
        AND generation_date >= reporting_period_end),
    CONSTRAINT ck_legacy_compliance_report_review CHECK (review_date IS NULL OR review_date >= generation_date),
    CONSTRAINT ck_legacy_compliance_report_submission CHECK (submission_date IS NULL OR status = 'SUBMITTED')
);

CREATE INDEX ix_legacy_compliance_report_type_period
    ON legacy_compliance_report (report_type, reporting_period_start, reporting_period_end);
CREATE INDEX ix_legacy_compliance_report_next_due ON legacy_compliance_report (next_report_due);

COMMENT ON TABLE legacy_compliance_report IS 'Read-only regulatory report history migrated from the monolith.';
