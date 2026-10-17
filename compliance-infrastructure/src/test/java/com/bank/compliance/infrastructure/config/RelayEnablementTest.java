package com.bank.compliance.infrastructure.config;

import com.bank.compliance.infrastructure.outbox.OutboxRelay;
import com.bank.compliance.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The relay publishes to Kafka only when compliance.outbox.relay.enabled is
 * true: without the property no relay bean exists, so a pod or a local run
 * without Kafka egress never starts sending.
 */
class RelayEnablementTest {

    @SuppressWarnings("unchecked")
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(app -> app.getBeanFactory()
            .setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
        .withBean(SpringDataOutboxRepository.class, () -> mock(SpringDataOutboxRepository.class))
        .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withUserConfiguration(OutboxConfiguration.RelayConfiguration.class);

    @Test
    void withoutThePropertyThereIsNoRelay() {
        context.run(app -> assertThat(app).doesNotHaveBean(OutboxRelay.class));
    }

    @Test
    void theRelayRunsOnlyWhenEnabled() {
        context.withPropertyValues("compliance.outbox.relay.enabled=true")
            .run(app -> assertThat(app).hasSingleBean(OutboxRelay.class));
        context.withPropertyValues("compliance.outbox.relay.enabled=false")
            .run(app -> assertThat(app).doesNotHaveBean(OutboxRelay.class));
    }
}
