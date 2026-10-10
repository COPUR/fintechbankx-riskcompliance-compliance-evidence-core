-- When the relay first failed to send a row. Kept for the record; since
-- ADR-021 decision 4 the relay no longer writes it (a non-payload failure
-- marks nothing on the row and is never parked). Un-parking may clear it.

ALTER TABLE outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;
