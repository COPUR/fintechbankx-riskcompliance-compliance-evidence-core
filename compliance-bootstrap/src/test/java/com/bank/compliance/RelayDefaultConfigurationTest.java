package com.bank.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml keeps the outbox relay off unless OUTBOX_RELAY_ENABLED is
 * set: local runs and pods without Kafka egress never send.
 */
class RelayDefaultConfigurationTest {

    @Test
    void theRelayIsOffUnlessThePodEnablesIt() throws Exception {
        assertThat(relayEnabled(Map.of())).isEqualTo("false");
        assertThat(relayEnabled(Map.of("OUTBOX_RELAY_ENABLED", "true"))).isEqualTo("true");
    }

    private static String relayEnabled(Map<String, Object> podEnv) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("pod-env", podEnv));
        for (PropertySource<?> document : documents) {
            if (document.getProperty("spring.config.activate.on-profile") == null) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return Binder.get(environment).bind("compliance.outbox.relay.enabled", String.class).orElse(null);
    }
}
