package com.bank.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway runs as the migration owner (DB_MIGRATION_USERNAME / _PASSWORD) and
 * grants the runtime role (DB_USERNAME) only what the service needs. Without
 * the migration credentials (local runs) it falls back to the app's own.
 */
class FlywayRoleConfigurationTest {

    @Test
    void flywayUsesTheMigrationOwnerAndGrantsTheRuntimeRole() throws Exception {
        FlywayProperties flyway = flyway(Map.of(
            "DB_USERNAME", "compliance_evidence_app",
            "SPRING_DATASOURCE_PASSWORD", "runtime-secret",
            "DB_MIGRATION_USERNAME", "compliance_evidence_owner",
            "DB_MIGRATION_PASSWORD", "owner-secret"));

        assertThat(flyway.getUser()).isEqualTo("compliance_evidence_owner");
        assertThat(flyway.getPassword()).isEqualTo("owner-secret");
        assertThat(flyway.getPlaceholders()).containsEntry("runtime_role", "compliance_evidence_app");
    }

    @Test
    void withoutMigrationCredentialsFlywayFallsBackToTheAppCredentials() throws Exception {
        FlywayProperties flyway = flyway(Map.of("SPRING_DATASOURCE_PASSWORD", "runtime-secret"));

        assertThat(flyway.getUser()).isEqualTo("compliance_evidence_app");
        assertThat(flyway.getPassword()).isEqualTo("runtime-secret");
        assertThat(flyway.getPlaceholders()).containsEntry("runtime_role", "compliance_evidence_app");
    }

    /**
     * Aurora TLS (cicd-templates 4f0f266): the chart and Terraform verify DB_URL
     * (sslmode=verify-full). Flyway must have no URL of its own, so the migration
     * owner connects through DB_URL as well and cannot bypass that check.
     */
    @Test
    void flywayHasNoUrlOfItsOwnAndConnectsThroughDbUrl() throws Exception {
        FlywayProperties flyway = flyway(Map.of(
            "DB_URL", "jdbc:postgresql://aurora:5432/db_cmp_evidence_prod?sslmode=verify-full",
            "DB_MIGRATION_USERNAME", "compliance_evidence_owner",
            "DB_MIGRATION_PASSWORD", "owner-secret"));

        assertThat(flyway.getUrl()).isNull();
    }

    private static FlywayProperties flyway(Map<String, Object> podEnv) throws Exception {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
            new org.springframework.core.env.SystemEnvironmentPropertySource("pod-env", podEnv));
        for (PropertySource<?> document : documents) {
            if (document.getProperty("spring.config.activate.on-profile") == null) {
                environment.getPropertySources().addAfter("pod-env", document);
            }
        }
        return Binder.get(environment).bind("spring.flyway", FlywayProperties.class).orElseGet(FlywayProperties::new);
    }
}
