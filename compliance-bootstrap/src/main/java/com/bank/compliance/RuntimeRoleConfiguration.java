package com.bank.compliance;

import com.bank.compliance.infrastructure.config.StartupAssertions;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * Registers {@link RuntimeRoleGuard} in the service context (component scan
 * from {@link ComplianceEvidenceApplication}; the migrate-only
 * {@link DatabaseMigration} imports its configurations by name and never this
 * one). The guard verifies while the bean is created, on the service's own
 * pool and after {@link FlywayMigrationInitializer} has validated the schema
 * history, so the context fails before the web server or the relay start.
 * On by default, like the TLS assertions ({@link StartupAssertions}), and off
 * only with the local profile (a single-user local run migrates as the role it
 * connects with, so it is its own owner); the test switch
 * fintechbankx.tls.enforce=false leaves it on, since tests connect as a
 * least-privilege runtime role and the integration tests prove it there.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeRoleConfiguration {

    static final String SCHEMA_PROPERTY = "spring.datasource.hikari.schema";

    @Bean
    @Conditional(StartupAssertions.NotLocal.class)
    RuntimeRoleGuard runtimeRoleGuard(DataSource dataSource, Environment environment,
                                      FlywayMigrationInitializer flywayHasValidated) {
        String schema = environment.getProperty(SCHEMA_PROPERTY, "").trim();
        if (schema.isEmpty()) {
            throw new IllegalStateException(SCHEMA_PROPERTY + " must name the service schema: the runtime role guard"
                    + " checks the runtime role's privileges on it");
        }
        RuntimeRoleGuard guard = new RuntimeRoleGuard(dataSource, schema);
        guard.verify();
        return guard;
    }
}
