-- Platform ruling on parked metrics: the relay counts every parked row once
-- in the counter outbox.parked.events (Prometheus outbox_parked_events_total).
-- Rows the relay parks are written with park_counted = true in the same
-- update. Rows an operator parks by hand (runbook UPDATE) keep the default
-- false; the relay counts them as OperatorPark on its next run and sets the
-- flag, so no row is counted twice. Un-parking resets it (runbook).

ALTER TABLE outbox_event ADD COLUMN park_counted BOOLEAN NOT NULL DEFAULT false;

-- Rows parked before this migration are not counted again.
UPDATE outbox_event SET park_counted = true WHERE parked_at IS NOT NULL;

CREATE INDEX ix_outbox_uncounted_park ON outbox_event (created_seq)
    WHERE parked_at IS NOT NULL AND NOT park_counted;
