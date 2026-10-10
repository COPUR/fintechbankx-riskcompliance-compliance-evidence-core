package com.bank.compliance.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link DatabaseTlsGuard} by default, wherever the service or its
 * migrate-only Job runs; it then requires DB_SSL_ROOT_CERT (the chart sets it
 * from the mounted RDS CA bundle on every container) and a datasource URL
 * the driver verifies against it. Only the local profile or the test switch
 * ({@link StartupAssertions}) leave the guard out, and then
 * {@link StartupAssertions.OffWarning} logs that the assertions are off.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseTlsConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any connection pool or Flyway bean exists. */
    @Bean
    @Conditional(StartupAssertions.On.class)
    static DatabaseTlsGuard databaseTlsGuard(Environment environment) {
        return new DatabaseTlsGuard(environment);
    }

    @Bean
    @Conditional(StartupAssertions.Off.class)
    static StartupAssertions.OffWarning startupAssertionsOffWarning() {
        return new StartupAssertions.OffWarning();
    }
}
