package com.bank.compliance.infrastructure.config;

import com.bank.compliance.infrastructure.outbox.OutboxRelay;
import com.bank.compliance.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxConfigurationTest {

    private final OutboxConfiguration configuration = new OutboxConfiguration();
    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);

    @Test
    void pendingGaugeUsesThePlatformMetricNameAndServiceTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.countByPublishedAtIsNull()).thenReturn(4L);

        configuration.outboxPendingEventsGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.pending.events").tag("service", "svc-cmp-evidence").gauge();
        assertThat(gauge.value()).isEqualTo(4.0);
    }

    @Test
    void providesTheEnvelopeFactoryAndAUtcClock() {
        assertThat(configuration.complianceEventEnvelopeFactory(new ObjectMapper())).isNotNull();
        assertThat(configuration.clock().getZone()).isEqualTo(Clock.systemUTC().getZone());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleRelaysAndPurgesThroughTheRelay() {
        OutboxConfiguration.RelayConfiguration relayConfiguration = new OutboxConfiguration.RelayConfiguration();
        OutboxRelay relay = relayConfiguration.outboxRelay(outbox, mock(KafkaTemplate.class),
            mock(PlatformTransactionManager.class), Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7));
        OutboxRelay spyRelay = org.mockito.Mockito.spy(relay);
        org.mockito.Mockito.doReturn(0).when(spyRelay).relayOnce();
        org.mockito.Mockito.doReturn(2).when(spyRelay).purgePublished();
        OutboxConfiguration.RelaySchedule schedule = relayConfiguration.relaySchedule(spyRelay);

        schedule.relay();
        schedule.purge();

        assertThat(relay).isNotNull();
        verify(spyRelay).relayOnce();
        verify(spyRelay).purgePublished();
        verify(outbox, org.mockito.Mockito.never()).tryRelayLock(anyLong());
    }
}
