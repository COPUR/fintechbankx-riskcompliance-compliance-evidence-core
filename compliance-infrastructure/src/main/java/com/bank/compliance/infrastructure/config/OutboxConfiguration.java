package com.bank.compliance.infrastructure.config;

import com.bank.compliance.infrastructure.outbox.ComplianceEventEnvelopeFactory;
import com.bank.compliance.infrastructure.outbox.OutboxRelay;
import com.bank.compliance.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

    /** Platform-wide outbox backlog series (Prometheus: outbox_pending_events). */
    static final String PENDING_GAUGE = "outbox.pending.events";
    /** Rows the relay parked (Prometheus: outbox_parked_events). */
    static final String PARKED_GAUGE = "outbox.parked.events";
    /** Age of the oldest event waiting to be relayed (Prometheus: outbox_oldest_pending_age_seconds). */
    static final String OLDEST_PENDING_AGE_GAUGE = "outbox.oldest.pending.age.seconds";
    static final String SERVICE_ID = "svc-cmp-evidence";

    @Bean
    ComplianceEventEnvelopeFactory complianceEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new ComplianceEventEnvelopeFactory(objectMapper);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Backlog of events not yet on Kafka. Alert on growth: it means the relay
     * or the brokers are down while screenings keep being recorded.
     */
    @Bean
    Gauge outboxPendingEventsGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder(PENDING_GAUGE, outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNull)
            .description("Compliance events written to the outbox but not yet published to Kafka")
            .tag("service", SERVICE_ID)
            .register(registry);
    }

    /**
     * How long the oldest unsent event has waited, 0 when none is waiting.
     * Measured from created_at. This is the alert signal (ADR-021 decision 4):
     * a non-payload failure (outage, credentials, ACL) stops the relay without
     * marking or parking anything, so the backlog ages until it is fixed.
     */
    @Bean
    Gauge outboxOldestPendingAgeGauge(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
        return Gauge.builder(OLDEST_PENDING_AGE_GAUGE, outbox, repository -> repository.findOldestPendingCreatedAt()
                .map(createdAt -> Math.max(0, Duration.between(createdAt, clock.instant()).toMillis()) / 1000.0)
                .orElse(0.0))
            .description("Seconds since the oldest compliance event still waiting for the outbox relay was written")
            .tag("service", SERVICE_ID)
            .baseUnit("seconds")
            .register(registry);
    }

    /**
     * Events the relay parked: payload errors only (ADR-021 decision 4), or
     * a manual park by an operator (runbook).
     * Alert when above zero: consumers miss these until they are replayed by
     * hand (runbook, "Parked outbox events").
     */
    @Bean
    Gauge outboxParkedEventsGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder(PARKED_GAUGE, outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNotNull)
            .description("Compliance events parked after a payload error or by an operator")
            .tag("service", SERVICE_ID)
            .register(registry);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Off unless compliance.outbox.relay.enabled=true
     * (OUTBOX_RELAY_ENABLED), so a pod or local run without Kafka never sends.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "compliance.outbox.relay.enabled", havingValue = "true", matchIfMissing = false)
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${compliance.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${compliance.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${compliance.outbox.retention:P7D}") Duration retention,
                                @Value("${compliance.outbox.relay.max-attempts:10}") int maxAttempts,
                                @Value("${compliance.outbox.relay.backoff-initial:PT1S}") Duration backoffInitial,
                                @Value("${compliance.outbox.relay.backoff-max:PT5M}") Duration backoffMax,
                                MeterRegistry meters) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                sendTimeout, retention, maxAttempts, backoffInitial, backoffMax, meters);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${compliance.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${compliance.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
