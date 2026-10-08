package com.bank.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service talks to Amazon MSK with IAM auth only under the "msk" profile
 * (set by the Helm chart); locally and in tests Kafka stays PLAINTEXT.
 */
class MskProfileConfigurationTest {

    @Test
    void defaultDocumentIsPlaintextAndTheMskProfileSwitchesToIamOverSaslSsl() throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));

        PropertySource<?> defaults = documents.getFirst();
        assertThat(defaults.getProperty("spring.kafka.security.protocol")).hasToString("${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}");
        assertThat(defaults.getProperty("spring.kafka.properties.sasl.mechanism")).isNull();
        assertThat(defaults.getProperty("compliance.outbox.relay.enabled")).hasToString("${OUTBOX_RELAY_ENABLED:true}");

        PropertySource<?> msk = documents.stream()
            .filter(d -> "msk".equals(String.valueOf(d.getProperty("spring.config.activate.on-profile"))))
            .findFirst().orElseThrow();
        assertThat(msk.getProperty("spring.kafka.security.protocol")).hasToString("SASL_SSL");
        assertThat(msk.getProperty("spring.kafka.properties.sasl.mechanism")).hasToString("AWS_MSK_IAM");
        assertThat(msk.getProperty("spring.kafka.properties.sasl.jaas.config"))
            .hasToString("software.amazon.msk.auth.iam.IAMLoginModule required;");
        String handler = String.valueOf(msk.getProperty("spring.kafka.properties.sasl.client.callback.handler.class"));
        assertThat(handler).isEqualTo("software.amazon.msk.auth.iam.IAMClientCallbackHandler");
    }

    @Test
    void mskIamLibraryIsOnTheRuntimeClasspath() throws Exception {
        assertThat(Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler")).isNotNull();
        assertThat(Class.forName("software.amazon.msk.auth.iam.IAMLoginModule")).isNotNull();
    }
}
