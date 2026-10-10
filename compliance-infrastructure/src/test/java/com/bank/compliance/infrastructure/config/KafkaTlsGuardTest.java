package com.bank.compliance.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The Kafka half of the startup TLS contract, the sibling of
 * {@link DatabaseTlsGuardTest}. In a deployed environment (the chart always
 * mounts the RDS CA bundle, so DB_SSL_ROOT_CERT is set) the outbox relay must
 * publish over TLS: SASL_SSL (Amazon MSK with IAM, profile kafka-msk) or SSL
 * (Strimzi mutual TLS with the KafkaUser certificate, profile kafka-strimzi).
 * PLAINTEXT, SASL_PLAINTEXT or no protocol at all (Kafka defaults to
 * PLAINTEXT) means a values override or a wrong profile, and the service
 * refuses to start rather than publish screening decisions in clear. The guard
 * reads the producer's effective security.protocol the way Spring Boot builds
 * it, so a producer-level override cannot hide a downgrade.
 *
 * <p>The guard is on by default, like DatabaseTlsGuard, relay on or off: the
 * relay flag decides only whether anything publishes, so a pod with a
 * plaintext producer is refused before the flag is ever turned on, not on the
 * day it is. Only the local profile and the test switch
 * fintechbankx.tls.enforce=false turn it off.
 */
class KafkaTlsGuardTest {

    private static final String PROTOCOL = "spring.kafka.security.protocol";
    private static final String REFUSED = "spring.kafka producer security.protocol must be SASL_SSL or SSL";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaTlsConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"SASL_SSL", "SSL"})
    void acceptsATlsProtocol(String protocol) {
        assertThatCode(() -> guard(environment(protocol)).verify()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAINTEXT", "SASL_PLAINTEXT"})
    void refusesAProtocolWithoutTls(String protocol) {
        assertThatThrownBy(() -> guard(environment(protocol)).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFUSED)
                .hasMessageContaining(protocol);
    }

    @Test
    void refusesAMissingProtocolWhichWouldDefaultToPlaintext() {
        assertThatThrownBy(() -> guard(new MockEnvironment()).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFUSED)
                .hasMessageContaining("none")
                .hasMessageContaining("PLAINTEXT");
    }

    /** The producer's own protocol wins over the common one, exactly as Spring Boot builds the producer. */
    @Test
    void readsTheEffectiveProducerProtocolNotOnlyTheCommonOne() {
        MockEnvironment downgraded = environment("SASL_SSL")
                .withProperty("spring.kafka.producer.security.protocol", "PLAINTEXT");
        MockEnvironment upgraded = environment("PLAINTEXT")
                .withProperty("spring.kafka.producer.security.protocol", "SSL");

        assertThatThrownBy(() -> guard(downgraded).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFUSED);
        assertThatCode(() -> guard(upgraded).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesAProtocolSetThroughTheRawProducerProperties() {
        MockEnvironment environment = environment("SASL_SSL")
                .withProperty("spring.kafka.producer.properties.security.protocol", "SASL_PLAINTEXT");

        assertThatThrownBy(() -> guard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFUSED);
    }

    /** The message names the property and the value, never a broker address. */
    @Test
    void theRefusalNamesNoBroker() {
        MockEnvironment environment = environment("PLAINTEXT")
                .withProperty("spring.kafka.bootstrap-servers", "b-1.msk.example.internal:9098");

        assertThatThrownBy(() -> guard(environment).verify())
                .hasMessageNotContaining("msk.example.internal");
    }

    @Test
    void refusesToStartTheContextWhenTheRelayWouldProduceInPlaintext() {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining(REFUSED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SASL_SSL", "SSL"})
    void startsTheContextWhenTheRelayProducesOverTls(String protocol) {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=true",
                        PROTOCOL + "=" + protocol)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    /** The relay flag gates publishing only: a plaintext producer is refused relay off too. */
    @ParameterizedTest
    @ValueSource(strings = {"PLAINTEXT", "SASL_PLAINTEXT"})
    void refusesToStartTheContextWithTheRelayOffWhenTheProducerWouldNotUseTls(String protocol) {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=false",
                        PROTOCOL + "=" + protocol)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining(REFUSED).hasMessageContaining(protocol));
    }

    /** Relay off and no protocol set means Kafka's PLAINTEXT default: refused. */
    @Test
    void refusesToStartTheContextWithTheRelayOffOrUnsetWhenNoProtocolIsSet() {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining(REFUSED).hasMessageContaining("none"));
        contextRunner
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining(REFUSED).hasMessageContaining("none"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SASL_SSL", "SSL"})
    void startsTheContextWithTheRelayOffWhenTheProducerIsOverTls(String protocol) {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=false",
                        PROTOCOL + "=" + protocol)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    /** On by default: no bundle, no profile and no switch is still a refusal, relay on, off or unset. */
    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void refusesToStartTheContextByDefaultWhenTheProducerWouldNotUseTls(String relayEnabled) {
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=" + relayEnabled, PROTOCOL + "=PLAINTEXT")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining(REFUSED).hasMessageContaining("PLAINTEXT"));
        contextRunner
                .withPropertyValues("compliance.outbox.relay.enabled=" + relayEnabled)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining(REFUSED).hasMessageContaining("none"));
        contextRunner
                .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(REFUSED));
    }

    /** The local profile and the test switch are the only ways off; the guard is then not registered. */
    @ParameterizedTest
    @ValueSource(strings = {"spring.profiles.active=local", "fintechbankx.tls.enforce=false"})
    void staysOffOnlyWithTheLocalProfileOrTheTestSwitch(String property) {
        contextRunner
                .withPropertyValues(property, "compliance.outbox.relay.enabled=true", PROTOCOL + "=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
        contextRunner
                .withPropertyValues(property)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fintechbankx.tls.enforce=true", "fintechbankx.tls.enforce=", "spring.profiles.active=LOCAL"})
    void staysOnForAnyOtherValueOrProfile(String property) {
        contextRunner
                .withPropertyValues(property, PROTOCOL + "=PLAINTEXT")
                .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(REFUSED));
    }

    private static MockEnvironment environment(String protocol) {
        return new MockEnvironment().withProperty(PROTOCOL, protocol);
    }

    private static KafkaTlsGuard guard(MockEnvironment environment) {
        KafkaProperties kafka = Binder.get(environment).bind("spring.kafka", KafkaProperties.class)
                .orElseGet(KafkaProperties::new);
        return new KafkaTlsGuard(kafka, new DefaultSslBundleRegistry());
    }
}
