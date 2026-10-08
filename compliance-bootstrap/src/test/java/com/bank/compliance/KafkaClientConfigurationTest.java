package com.bank.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer settings the outbox relay gets from application.yml, per the
 * platform Kafka client guide: PLAINTEXT by default, IAM over SASL_SSL under
 * "kafka-msk" (the Helm default) and PEM mutual TLS under "kafka-strimzi".
 */
class KafkaClientConfigurationTest {

    @Test
    void defaultProducerFollowsThePlatformGuide() throws Exception {
        Map<String, Object> producer = producerProperties(null);

        assertThat(producer).containsEntry("security.protocol", "PLAINTEXT")
            .containsEntry("client.id", "svc-cmp-evidence")
            .containsEntry("acks", "all")
            .containsEntry("compression.type", "lz4")
            .containsEntry("linger.ms", "5")
            .containsEntry("enable.idempotence", "true")
            .containsEntry("max.in.flight.requests.per.connection", "5")
            .containsEntry("delivery.timeout.ms", "30000")
            .doesNotContainKey("sasl.mechanism");
        assertThat(kafka(null).getAdmin().isAutoCreate()).as("services never create topics").isFalse();
    }

    @Test
    void kafkaMskProfileUsesIamOverSaslSsl() throws Exception {
        Map<String, Object> producer = producerProperties("kafka-msk");

        assertThat(producer).containsEntry("security.protocol", "SASL_SSL")
            .containsEntry("sasl.mechanism", "AWS_MSK_IAM")
            .containsEntry("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;")
            .containsEntry("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler")
            .containsEntry("client.id", "svc-cmp-evidence");
    }

    @Test
    void kafkaStrimziProfileUsesPemMutualTlsFromTheEnvironment() throws Exception {
        Map<String, Object> producer = producerProperties("kafka-strimzi");

        assertThat(producer).containsEntry("security.protocol", "SSL")
            .containsEntry("ssl.keystore.type", "PEM")
            .containsEntry("ssl.truststore.type", "PEM")
            .containsEntry("ssl.keystore.certificate.chain", "cert-from-secret")
            .containsEntry("ssl.keystore.key", "key-from-secret")
            .containsEntry("ssl.truststore.certificates", "ca-from-secret")
            .doesNotContainKey("sasl.mechanism");
    }

    @Test
    void mskIamLibraryIsOnTheRuntimeClasspath() throws Exception {
        assertThat(Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler")).isNotNull();
        assertThat(Class.forName("software.amazon.msk.auth.iam.IAMLoginModule")).isNotNull();
    }

    private static Map<String, Object> producerProperties(String profile) throws Exception {
        return kafka(profile).buildProducerProperties(null);
    }

    private static KafkaProperties kafka(String profile) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("pod-env", Map.of(
            "KAFKA_TLS_CERT", "cert-from-secret", "KAFKA_TLS_KEY", "key-from-secret", "KAFKA_TLS_CA", "ca-from-secret")));
        // Later documents win, like Spring Boot's profile-specific documents.
        for (PropertySource<?> document : documents) {
            Object activation = document.getProperty("spring.config.activate.on-profile");
            if (activation == null || String.valueOf(activation).equals(profile)) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return Binder.get(environment).bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
    }
}
