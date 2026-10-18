-- svc-cmp-evidence owns these tables. Schema: sc_cmp_evidence (Flyway runs
-- with that schema as default, so names are unqualified).
--
-- Transaction compliance screening results: evidence, written once per
-- transaction and never updated. transaction_id is the caller's id (for
-- example a payment id) and is unique, so a retried screening returns the
-- first result.
--
-- The screened facts (amount, currency, sanctions/KYC/PEP flags) and the rule
-- set version are stored with the decision: a replay must state the same facts
-- to get the stored result back, and an auditor can see what was decided on.
-- The flags are attested by the caller (attestation_source); see
-- docs/architecture/decisions/0001-screening-facts-are-caller-attested.md.

CREATE TABLE compliance_screening (
    screening_id    VARCHAR(64)    PRIMARY KEY,
    transaction_id  VARCHAR(128)   NOT NULL,
    customer_id     VARCHAR(128)   NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    sanctions_hit   BOOLEAN        NOT NULL,
    kyc_verified    BOOLEAN        NOT NULL,
    pep             BOOLEAN        NOT NULL,
    attestation_source VARCHAR(32) NOT NULL,
    decision        VARCHAR(16)    NOT NULL,
    reasons         JSONB          NOT NULL DEFAULT '[]'::jsonb,
    rule_set_version VARCHAR(64)   NOT NULL,
    checked_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_compliance_screening_transaction UNIQUE (transaction_id),
    CONSTRAINT ck_compliance_screening_amount CHECK (amount > 0),
    CONSTRAINT ck_compliance_screening_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_compliance_screening_attestation CHECK (attestation_source IN ('CALLER_ATTESTED')),
    CONSTRAINT ck_compliance_screening_decision CHECK (decision IN ('PASS', 'REVIEW', 'FAIL')),
    CONSTRAINT ck_compliance_screening_reasons CHECK (jsonb_typeof(reasons) = 'array')
);

CREATE INDEX ix_compliance_screening_customer ON compliance_screening (customer_id, checked_at);
CREATE INDEX ix_compliance_screening_review_queue ON compliance_screening (checked_at) WHERE decision = 'REVIEW';

COMMENT ON TABLE compliance_screening IS 'Transaction compliance screening evidence; insert-only.';
