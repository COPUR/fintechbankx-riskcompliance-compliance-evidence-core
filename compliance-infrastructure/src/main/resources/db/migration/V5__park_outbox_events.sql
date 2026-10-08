-- Outbox rows the relay can never send (a non-retryable producer error such
-- as RecordTooLargeException, SerializationException, InvalidTopicException
-- or TopicAuthorizationException, or compliance.outbox.relay.max-attempts
-- failed sends) are parked instead of holding back every later event.
-- parked_at is set when the relay gives up; last_error (V3) keeps the reason.
-- The relay skips parked rows; the outbox.parked.events gauge counts them.
-- Manual replay clears parked_at (see the runbook, "Parked outbox events").

ALTER TABLE outbox_event ADD COLUMN parked_at TIMESTAMPTZ;

-- The relay reads rows that are neither published nor parked, in insertion order.
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_parked ON outbox_event (parked_at) WHERE published_at IS NULL AND parked_at IS NOT NULL;
