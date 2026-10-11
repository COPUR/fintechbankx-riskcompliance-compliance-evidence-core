package com.bank.compliance;

import com.bank.compliance.infrastructure.config.StartupAssertions;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * Registers {@link RuntimeRoleGuard} in the service context (component scan
 * from {@link ComplianceEvidenceApplication}; the migrate-only
 * {@link DatabaseMigration} imports its configurations by name and never this
 * one). The guard is a SmartInitializingSingleton: it verifies once every
 * singleton exists, on the service's own pool and after
 * {@link FlywayMigrationInitializer}, which it depends on, has validated the
 * schema history, so the context fails before the web server or the relay
 * start. The bean is {@code @Lazy(false)} explicitly: Spring Boot's
 * {@code spring.main.lazy-initialization=true} (SPRING_MAIN_LAZY_INITIALIZATION
 * in a pod's environment; the chart refuses every spring.main.* key) makes
 * only the definitions lazy that do not set lazy-init themselves, so a pod
 * started that way still creates the guard, which still pulls Flyway's
 * validate, and still refuses. On by default, like the TLS assertions
 * ({@link StartupAssertions}), and off only with the local profile (a
 * single-user local run migrates as the role it connects with, so it is its own
 * owner); the test switch fintechbankx.tls.enforce=false leaves it on, since
 * tests connect as a least-privilege runtime role and the integration tests
 * prove it there.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeRoleConfiguration {

    static final String SCHEMA_PROPERTY = "spring.datasource.hikari.schema";

    @Bean
    @Lazy(false)
    @Conditional(StartupAssertions.NotLocal.class)
    RuntimeRoleGuard runtimeRoleGuard(DataSource dataSource, Environment environment,
                                      FlywayMigrationInitializer flywayHasValidated) {
        String schema = environment.getProperty(SCHEMA_PROPERTY, "").trim();
        if (schema.isEmpty()) {
            throw new IllegalStateException(SCHEMA_PROPERTY + " must name the service schema: the runtime role guard"
                    + " checks the runtime role's privileges on it");
        }
        return new RuntimeRoleGuard(dataSource, schema);
    }
}
