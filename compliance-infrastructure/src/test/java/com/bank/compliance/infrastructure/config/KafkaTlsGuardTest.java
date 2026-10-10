package com.bank.compliance.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Kafka half of the startup TLS contract. Where the RDS CA bundle is
 * mounted (DB_SSL_ROOT_CERT, always in the chart) the service talks to Amazon
 * MSK, and the outbox relay may only produce over SASL_SSL (IAM). The guard
 * reads the producer's effective security.protocol the way Spring Boot builds
 * it, so a producer-level override cannot hide a downgrade. It is active only
 * when the relay is on, and never without the bundle, so the local Strimzi
 * mutual-TLS profile (SSL) and plain local runs keep starting.
 */
class KafkaTlsGuardTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String PROTOCOL = "spring.kafka.security.protocol";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaTlsConfiguration.class);

    @Test
    void acceptsSaslSsl() {
        assertThatCode(() -> guard(new MockEnvironment().withProperty(PROTOCOL, "SASL_SSL")).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void refusesTheDefaultPlaintextProducer() {
        assertThatThrownBy(() -> guard(new MockEnvironment()).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.protocol must be SASL_SSL")
                .hasMessageContaining("PLAINTEXT");
    }

    @Test
    void refusesStrimziStyleSslBecauseMskNeedsIam() {
        assertThatThrownBy(() -> guard(new MockEnvironment().withProperty(PROTOCOL, "SSL")).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.protocol must be SASL_SSL");
    }

    @Test
    void readsTheEffectiveProducerProtocolSoAProducerLevelOverrideIsCaught() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PROTOCOL, "SASL_SSL")
                .withProperty("spring.kafka.producer.properties.security.protocol", "PLAINTEXT");

        assertThatThrownBy(() -> guard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.protocol must be SASL_SSL");
    }

    @Test
    void readsAProducerLevelProtocolThatUpgradesTheCommonOne() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PROTOCOL, "PLAINTEXT")
                .withProperty("spring.kafka.producer.security.protocol", "SASL_SSL");

        assertThatCode(() -> guard(environment).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartTheContextWhenTheRelayWouldProduceInPlaintext() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "compliance.outbox.relay.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("security.protocol must be SASL_SSL"));
    }

    @Test
    void startsTheContextWhenTheRelayProducesOverSaslSsl() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "compliance.outbox.relay.enabled=true",
                        PROTOCOL + "=SASL_SSL")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    @Test
    void staysInactiveWhileTheRelayIsOffEvenWithTheBundle() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "compliance.outbox.relay.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    @Test
    void staysInactiveWithoutTheBundleSoTheLocalStrimziMutualTlsProfileStarts() {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=true", PROTOCOL + "=SSL")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    private static KafkaTlsGuard guard(MockEnvironment environment) {
        KafkaProperties kafka = Binder.get(environment).bind("spring.kafka", KafkaProperties.class)
                .orElseGet(KafkaProperties::new);
        return new KafkaTlsGuard(kafka, new DefaultSslBundleRegistry());
    }
}
