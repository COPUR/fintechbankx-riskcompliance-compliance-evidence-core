package com.bank.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
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
 * Platform alert rules select outbox meters by the common tags {@code app}
 * (the chart's service account) and {@code squad}. The chart passes both as
 * METRICS_APP and METRICS_SQUAD.
 */
class MetricTagsConfigurationTest {

    @Test
    void everyMeterCarriesTheAppAndSquadTagsFromThePodEnvironment() throws Exception {
        Map<String, String> tags = commonTags(Map.of(
            "METRICS_APP", "compliance-evidence-service",
            "METRICS_SQUAD", "compliance"));

        assertThat(tags).containsEntry("app", "compliance-evidence-service").containsEntry("squad", "compliance");
    }

    @Test
    void localRunsFallBackToTheServiceAccountNameAndSquad() throws Exception {
        assertThat(commonTags(Map.of()))
            .containsEntry("app", "compliance-evidence-service").containsEntry("squad", "compliance");
    }

    private static Map<String, String> commonTags(Map<String, Object> podEnv) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("pod-env", podEnv));
        for (PropertySource<?> document : documents) {
            if (document.getProperty("spring.config.activate.on-profile") == null) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return Binder.get(environment)
            .bind("management.metrics.tags", Bindable.mapOf(String.class, String.class))
            .orElseGet(Map::of);
    }
}
