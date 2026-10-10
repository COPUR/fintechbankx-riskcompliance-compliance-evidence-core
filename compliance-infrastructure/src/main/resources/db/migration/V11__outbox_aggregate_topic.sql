-- ADR-019 (owner decision 2026-10-08): one Kafka topic per aggregate. Every
-- event of the screening aggregate goes to evt.cmp.compliance.v1; the event is
-- named by its eventType (envelope and record header), not by the topic. The
-- per-event topic evt.cmp.compliance.screened.v1 is retired before it was
-- ever created (ADR-019 section 8), so there is no dual-publish.
--
-- Rows not yet sent (pending, or parked and waiting for a manual replay) are
-- moved to the aggregate topic. Sent rows keep the topic they went to: that
-- is history, and the retention purge removes them.

UPDATE outbox_event
   SET topic = 'evt.cmp.compliance.v1'
 WHERE published_at IS NULL
   AND topic <> 'evt.cmp.compliance.v1';

-- Still only this service's namespace, and an unsent row must name an
-- aggregate topic (evt.cmp.compliance.v<N>), never a per-event one.
ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_topic_namespace;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_topic_namespace
    CHECK (topic LIKE 'evt.cmp.compliance.%'
           AND (published_at IS NOT NULL OR topic ~ '^evt\.cmp\.compliance\.v[0-9]+$'));
