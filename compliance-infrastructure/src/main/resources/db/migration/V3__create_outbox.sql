-- Transactional outbox for the evt.cmp.compliance namespace. A row is written
-- in the same transaction as the compliance_screening row it describes and
-- relayed to Kafka by OutboxRelay. Payloads carry ids, the decision and
-- reason codes only, never the screening inputs.

CREATE TABLE outbox_event (
    event_id          UUID          PRIMARY KEY,
    created_seq       BIGINT        GENERATED ALWAYS AS IDENTITY,
    aggregate_type    VARCHAR(64)   NOT NULL,
    aggregate_id      VARCHAR(64)   NOT NULL,
    aggregate_version BIGINT        NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    topic             VARCHAR(249)  NOT NULL,
    payload           JSONB         NOT NULL,
    correlation_id    VARCHAR(128)  NOT NULL,
    occurred_at       TIMESTAMPTZ   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    last_error        VARCHAR(512),

    CONSTRAINT uq_outbox_created_seq UNIQUE (created_seq),
    CONSTRAINT ck_outbox_topic_namespace CHECK (topic LIKE 'evt.cmp.compliance.%')
);

-- The relay reads unpublished rows in insertion order.
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published_at ON outbox_event (published_at) WHERE published_at IS NOT NULL;

COMMENT ON TABLE outbox_event IS 'Pending and recently published compliance events; purged after compliance.outbox.retention.';
