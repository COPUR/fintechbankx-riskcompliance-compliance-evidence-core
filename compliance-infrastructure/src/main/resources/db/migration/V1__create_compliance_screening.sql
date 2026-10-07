-- svc-cmp-evidence owns these tables. Schema: sc_cmp_evidence (Flyway runs
-- with that schema as default, so names are unqualified).
--
-- Transaction compliance screening results: evidence, written once per
-- transaction and never updated. transaction_id is the caller's id (for
-- example a payment id) and is unique, so a retried screening returns the
-- first result.

CREATE TABLE compliance_screening (
    screening_id    VARCHAR(64)    PRIMARY KEY,
    transaction_id  VARCHAR(128)   NOT NULL,
    customer_id     VARCHAR(128)   NOT NULL,
    decision        VARCHAR(16)    NOT NULL,
    reasons         JSONB          NOT NULL DEFAULT '[]'::jsonb,
    checked_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_compliance_screening_transaction UNIQUE (transaction_id),
    CONSTRAINT ck_compliance_screening_decision CHECK (decision IN ('PASS', 'REVIEW', 'FAIL')),
    CONSTRAINT ck_compliance_screening_reasons CHECK (jsonb_typeof(reasons) = 'array')
);

CREATE INDEX ix_compliance_screening_customer ON compliance_screening (customer_id, checked_at);
CREATE INDEX ix_compliance_screening_review_queue ON compliance_screening (checked_at) WHERE decision = 'REVIEW';

COMMENT ON TABLE compliance_screening IS 'Transaction compliance screening evidence; insert-only.';
