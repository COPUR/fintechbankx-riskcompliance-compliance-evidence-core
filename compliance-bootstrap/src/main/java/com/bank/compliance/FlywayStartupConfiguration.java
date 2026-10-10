package com.bank.compliance;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * What Flyway does when a context starts (compliance.database.flyway).
 *
 * <p>The service validates: it connects as the runtime role, which may read
 * but not write the schema history (V12), and refuses to start while a
 * migration is pending or an applied one was changed. Only the chart's
 * migration Job ({@link DatabaseMigration}, profile db-migrate) migrates, as
 * the schema owner, so the service pods never hold the owner's credential.
 * A value other than validate or migrate fails the startup.
 *
 * <p>Also registers {@link MigrationRoleGuard}: whenever both DB_MIGRATION_USERNAME
 * and DB_USERNAME are known they must be two roles, so a migration secret that
 * holds the runtime credential never migrates (decision 0002).
 */
@Configuration(proxyBeanMethods = false)
public class FlywayStartupConfiguration {

    static final String PROPERTY = "compliance.database.flyway";

    enum Mode { VALIDATE, MIGRATE }

    /** Static: a BeanFactoryPostProcessor runs before the datasource and Flyway beans exist. */
    @Bean
    static MigrationRoleGuard migrationRoleGuard(Environment environment) {
        return new MigrationRoleGuard(environment);
    }

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(Environment environment) {
        return strategy(mode(environment));
    }

    static Mode mode(Environment environment) {
        return Binder.get(environment).bind(PROPERTY, Mode.class).orElse(Mode.VALIDATE);
    }

    static FlywayMigrationStrategy strategy(Mode mode) {
        return switch (mode) {
            case VALIDATE -> flyway -> flyway.validate();
            case MIGRATE -> flyway -> flyway.migrate();
        };
    }
}
