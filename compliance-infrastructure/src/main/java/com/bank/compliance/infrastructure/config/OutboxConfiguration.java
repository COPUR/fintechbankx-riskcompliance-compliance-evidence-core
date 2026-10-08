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
        return Gauge.builder(PENDING_GAUGE, outbox, SpringDataOutboxRepository::countByPublishedAtIsNull)
            .description("Compliance events written to the outbox but not yet published to Kafka")
            .tag("service", SERVICE_ID)
            .register(registry);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Disable with compliance.outbox.relay.enabled=false
     * (tests, or a dedicated relay deployment).
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "compliance.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${compliance.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${compliance.outbox.relay.send-timeout:PT10S}") Duration sendTimeout,
                                @Value("${compliance.outbox.retention:P7D}") Duration retention) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize, sendTimeout, retention);
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
