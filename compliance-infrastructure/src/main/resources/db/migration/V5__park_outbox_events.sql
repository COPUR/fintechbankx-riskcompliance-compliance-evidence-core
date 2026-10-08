-- Outbox rows whose payload can never be sent (RecordTooLargeException,
-- SerializationException, InvalidTopicException) are parked instead of
-- holding back every later event (ADR-021 decision 4). Any other failure
-- stops the relay without marking the row and is never parked.
-- parked_at is set when the relay parks a row (or an operator, by hand);
-- last_error (V3) keeps the reason.
-- The relay skips parked rows; the outbox.parked.events gauge counts them.
-- Manual replay clears parked_at (see the runbook, "Parked outbox events").

ALTER TABLE outbox_event ADD COLUMN parked_at TIMESTAMPTZ;

-- The relay reads rows that are neither published nor parked, in insertion order.
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_parked ON outbox_event (parked_at) WHERE published_at IS NULL AND parked_at IS NOT NULL;
