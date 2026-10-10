-- Step 1 of the compliance report data split: a staging table in the
-- COMPLIANCE SERVICE database that receives a CSV copy of the monolith's
-- public.compliance_reports (enterprise-loan-management-system
-- V13__Create_compliance_reports_table.sql).

\set ON_ERROR_STOP on

DROP SCHEMA IF EXISTS backfill_stage CASCADE;
CREATE SCHEMA backfill_stage;

CREATE TABLE backfill_stage.compliance_reports (
    report_id               VARCHAR(30)    PRIMARY KEY,
    report_type             VARCHAR(50)    NOT NULL,
    generation_date         DATE           NOT NULL,
    reporting_period_start  DATE           NOT NULL,
    reporting_period_end    DATE           NOT NULL,
    total_loans             INTEGER        NOT NULL,
    total_amount            NUMERIC(20, 2) NOT NULL,
    high_risk_loans         INTEGER,
    compliance_score        NUMERIC(5, 2),
    regulatory_findings     INTEGER,
    findings_details        JSONB,
    report_data             JSONB,
    report_file_path        VARCHAR(500),
    generated_by            VARCHAR(100)   NOT NULL,
    reviewed_by             VARCHAR(100),
    review_date             DATE,
    status                  VARCHAR(30),
    submission_date         DATE,
    regulator_reference     VARCHAR(50),
    next_report_due         DATE,
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ,
    version                 INTEGER
);
