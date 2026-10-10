package com.bank.compliance.infrastructure.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link KafkaTlsGuard} when the outbox relay is on and the chart has
 * mounted the RDS CA bundle (DB_SSL_ROOT_CERT), the same condition as
 * {@link DatabaseTlsConfiguration}. The guard verifies while the bean is
 * created, so the context (and with it the pod) fails to start on a producer
 * that would not use SASL_SSL. Not imported by the migrate-only
 * {@code DatabaseMigration} context, which has no Kafka.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "compliance.outbox.relay.enabled", havingValue = "true")
public class KafkaTlsConfiguration {

    @Bean
    @ConditionalOnProperty(DatabaseTlsGuard.ROOT_CERT_PROPERTY)
    KafkaTlsGuard kafkaTlsGuard(KafkaProperties kafka, ObjectProvider<SslBundles> sslBundles) {
        KafkaTlsGuard guard = new KafkaTlsGuard(kafka, sslBundles.getIfAvailable(DefaultSslBundleRegistry::new));
        guard.verify();
        return guard;
    }
}
