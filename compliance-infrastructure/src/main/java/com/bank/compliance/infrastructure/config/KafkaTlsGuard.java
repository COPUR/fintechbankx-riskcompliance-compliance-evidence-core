package com.bank.compliance.infrastructure.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;

/**
 * Kafka TLS at startup, the sibling of {@link DatabaseTlsGuard}. Where the RDS
 * CA bundle is mounted (DB_SSL_ROOT_CERT, always in the chart) the service runs
 * on AWS and its outbox relay produces to Amazon MSK, which the platform
 * contract reaches over SASL_SSL with IAM. The relay must not start with a
 * producer that would send in PLAINTEXT or with Strimzi-style mutual TLS (SSL),
 * so this guard reads the producer's <em>effective</em> {@code security.protocol}
 * exactly as Spring Boot builds the producer properties (common settings, then
 * {@code spring.kafka.properties}, then the producer's own, then
 * {@code spring.kafka.producer.properties}) and refuses to start unless it is
 * {@value #REQUIRED_PROTOCOL}.
 *
 * <p>Registered by {@link KafkaTlsConfiguration} only when DB_SSL_ROOT_CERT is
 * set and the relay is on ({@code compliance.outbox.relay.enabled=true}).
 * Local runs and the in-cluster Strimzi profile have no bundle, so the guard
 * stays off for them.
 */
public final class KafkaTlsGuard {

    static final String REQUIRED_PROTOCOL = "SASL_SSL";
    static final String SECURITY_PROTOCOL = "security.protocol";

    private final KafkaProperties kafka;
    private final SslBundles sslBundles;

    KafkaTlsGuard(KafkaProperties kafka, SslBundles sslBundles) {
        this.kafka = kafka;
        this.sslBundles = sslBundles;
    }

    /** Kafka's own default when no security.protocol is set anywhere. */
    static final String KAFKA_DEFAULT_PROTOCOL = "PLAINTEXT";

    void verify() {
        Object protocol = kafka.buildProducerProperties(sslBundles).get(SECURITY_PROTOCOL);
        if (!REQUIRED_PROTOCOL.equals(protocol)) {
            throw new IllegalStateException("spring.kafka producer " + SECURITY_PROTOCOL + " must be "
                    + REQUIRED_PROTOCOL + " (Amazon MSK with IAM, profile kafka-msk) while "
                    + DatabaseTlsGuard.ROOT_CERT_PROPERTY + " is set and the outbox relay is enabled, got "
                    + (protocol == null ? KAFKA_DEFAULT_PROTOCOL + " (the Kafka default, nothing set)" : protocol));
        }
    }
}
