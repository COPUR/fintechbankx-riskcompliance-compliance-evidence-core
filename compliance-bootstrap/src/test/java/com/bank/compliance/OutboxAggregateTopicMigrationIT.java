package com.bank.compliance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-019 section 8: per-event topics are replaced by the aggregate topic
 * evt.cmp.compliance.v1. Outbox rows store their topic, so rows written before
 * the change and not yet sent (pending or parked) must be rewritten by a new
 * migration, and no unsent row may name a per-event topic afterwards.
 *
 * Runs the real migrations on a scratch schema as the migration owner
 * (single-user run, so V7 separates nothing): first up to V10, then rows in
 * the old shape, then the rest.
 */
class OutboxAggregateTopicMigrationIT {

    private static final String SCHEMA = "sc_cmp_evidence_topic_it";
    private static final String OLD_TOPIC = "evt.cmp.compliance.screened.v1";
    private static final String AGGREGATE_TOPIC = "evt.cmp.compliance.v1";

    private static JdbcTemplate owner;

    @BeforeAll
    static void scratchSchema() {
        PostgresTestDatabase.assumeAvailable();
        owner = PostgresTestDatabase.owner();
        owner.execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @AfterAll
    static void dropScratchSchema() {
        if (owner != null) {
            owner.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }

    @Test
    void unsentRowsMoveToTheAggregateTopicAndSentRowsKeepTheirHistory() {
        flyway("10").migrate();
        UUID pending = insert(OLD_TOPIC, "null", "null");
        UUID parked = insert(OLD_TOPIC, "null", "now()");
        UUID published = insert(OLD_TOPIC, "now()", "null");

        flyway(null).migrate();

        assertThat(topic(pending)).isEqualTo(AGGREGATE_TOPIC);
        assertThat(topic(parked)).as("a parked row is replayed by hand to the aggregate topic").isEqualTo(AGGREGATE_TOPIC);
        assertThat(topic(published)).as("where a sent row went is history").isEqualTo(OLD_TOPIC);

        assertThatThrownBy(() -> insert(OLD_TOPIC, "null", "null"))
            .as("no new unsent row may name a per-event topic")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("evt.rsk.risk.v1", "null", "null"))
            .as("still only this service's namespace")
            .isInstanceOf(DataIntegrityViolationException.class);
        UUID fresh = insert(AGGREGATE_TOPIC, "null", "null");
        assertThat(topic(fresh)).isEqualTo(AGGREGATE_TOPIC);
    }

    private static Flyway flyway(String target) {
        String ownerUser = owner.queryForObject("select current_user", String.class);
        var config = Flyway.configure()
            .dataSource(owner.getDataSource())
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .placeholders(Map.of("runtime_role", ownerUser))
            .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static UUID insert(String topic, String publishedAt, String parkedAt) {
        UUID id = UUID.randomUUID();
        owner.update("""
            insert into %s.outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
                                         payload, correlation_id, occurred_at, published_at, parked_at)
            values (?, 'ComplianceScreening', ?, 0, 'Compliance.ComplianceScreening.Screened.v1', ?,
                    '{}'::jsonb, 'corr-mig', now(), %s, %s)
            """.formatted(SCHEMA, publishedAt, parkedAt), id, "CMP-" + id, topic);
        return id;
    }

    private static String topic(UUID id) {
        return owner.queryForObject("select topic from " + SCHEMA + ".outbox_event where event_id = ?", String.class, id);
    }
}
