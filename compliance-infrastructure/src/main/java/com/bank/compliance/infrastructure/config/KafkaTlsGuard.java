package com.bank.compliance.infrastructure.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;

import java.util.Set;

/**
 * Kafka transport at startup, the sibling of {@link DatabaseTlsGuard}. The
 * outbox relay publishes screening decisions over TLS: SASL_SSL to Amazon MSK
 * with IAM authentication (profile kafka-msk) or SSL with the Strimzi
 * KafkaUser client certificate (profile kafka-strimzi, mutual TLS). A values
 * override (a producer-level property, a wrong profile) could downgrade the
 * producer to PLAINTEXT or SASL_PLAINTEXT, so this guard reads the producer's
 * <em>effective</em> {@code security.protocol} exactly as Spring Boot builds
 * the producer properties (common settings, then {@code spring.kafka.properties},
 * then the producer's own, then {@code spring.kafka.producer.properties}) and
 * refuses to start unless it is one of {@link #ACCEPTED_PROTOCOLS}.
 *
 * <p>Registered by {@link KafkaTlsConfiguration} by default, relay on or off:
 * the relay flag ({@code compliance.outbox.relay.enabled}) gates publishing
 * only, so a misconfigured producer is caught before the flag is turned on.
 * Only the local profile or the test switch ({@link StartupAssertions}) leave
 * it out. Messages name the property and the value, never a broker address.
 */
public final class KafkaTlsGuard {

    static final Set<String> ACCEPTED_PROTOCOLS = Set.of("SASL_SSL", "SSL");
    static final String SECURITY_PROTOCOL = "security.protocol";

    private final KafkaProperties kafka;
    private final SslBundles sslBundles;

    KafkaTlsGuard(KafkaProperties kafka, SslBundles sslBundles) {
        this.kafka = kafka;
        this.sslBundles = sslBundles;
    }

    void verify() {
        Object protocol = kafka.buildProducerProperties(sslBundles).get(SECURITY_PROTOCOL);
        if (protocol == null || !ACCEPTED_PROTOCOLS.contains(protocol.toString())) {
            throw new IllegalStateException("spring.kafka producer " + SECURITY_PROTOCOL
                    + " must be SASL_SSL or SSL, relay on or off (Amazon MSK with IAM authentication, profile"
                    + " kafka-msk, or Strimzi mutual TLS, profile kafka-strimzi); the TLS startup assertions are on"
                    + " by default and only the Spring profile " + StartupAssertions.LOCAL_PROFILE + " or "
                    + StartupAssertions.ENFORCE_PROPERTY + "=false (tests) turn them off; got "
                    + (protocol == null ? "none (Kafka defaults to PLAINTEXT)" : protocol));
        }
    }
}
