package com.bank.compliance.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link KafkaTlsGuard} by default, under the same switch as
 * {@link DatabaseTlsConfiguration} ({@link StartupAssertions}: only the local
 * profile or the test switch leave it out). The outbox relay flag
 * ({@code compliance.outbox.relay.enabled}) gates publishing, not this guard:
 * a pod whose producer would not use TLS is refused before the relay is ever
 * turned on, not on the day it is. The guard is a BeanFactoryPostProcessor, so
 * the context (and with it the pod) fails before any Kafka bean exists, and
 * {@code spring.main.lazy-initialization=true} cannot skip it. Not imported by
 * the migrate-only {@code DatabaseMigration} context, which has no Kafka.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTlsConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any Kafka producer or relay bean exists. */
    @Bean
    @Conditional(StartupAssertions.On.class)
    static KafkaTlsGuard kafkaTlsGuard(Environment environment) {
        return new KafkaTlsGuard(environment);
    }
}
