-- When the relay first failed to send a row. A retriable failure (broker or
-- egress outage, missing topic, timeout) parks a row only once it has kept
-- failing for longer than compliance.outbox.relay.retryable-park-after
-- (default 24 hours) measured from here; non-retriable failures park at once.
-- Un-parking clears it with parked_at (runbook, "Parked outbox events").

ALTER TABLE outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;
