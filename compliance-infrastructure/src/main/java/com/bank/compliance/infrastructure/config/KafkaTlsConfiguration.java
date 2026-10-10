package com.bank.compliance.infrastructure.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link KafkaTlsGuard} when the chart has mounted the RDS CA bundle
 * (DB_SSL_ROOT_CERT), the same and only condition as
 * {@link DatabaseTlsConfiguration}. The outbox relay flag
 * ({@code compliance.outbox.relay.enabled}) gates publishing, not this guard:
 * a pod whose producer would not use TLS is refused before the relay is ever
 * turned on, not on the day it is. The guard verifies while the bean is
 * created, so the context (and with it the pod) fails to start on a producer
 * that would not use TLS (SASL_SSL for MSK, SSL for Strimzi mutual TLS). Not
 * imported by the migrate-only {@code DatabaseMigration} context, which has no
 * Kafka.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTlsConfiguration {

    @Bean
    @ConditionalOnProperty(DatabaseTlsGuard.ROOT_CERT_PROPERTY)
    KafkaTlsGuard kafkaTlsGuard(KafkaProperties kafka, ObjectProvider<SslBundles> sslBundles) {
        KafkaTlsGuard guard = new KafkaTlsGuard(kafka, sslBundles.getIfAvailable(DefaultSslBundleRegistry::new));
        guard.verify();
        return guard;
    }
}
