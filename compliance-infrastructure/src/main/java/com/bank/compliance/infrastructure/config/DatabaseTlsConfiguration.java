package com.bank.compliance.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link DatabaseTlsGuard} when the chart has mounted the RDS CA
 * bundle (DB_SSL_ROOT_CERT is always set there). Local runs and tests have no
 * bundle and connect without TLS, so the guard stays off for them.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseTlsConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any connection pool or Flyway bean exists. */
    @Bean
    @ConditionalOnProperty(DatabaseTlsGuard.ROOT_CERT_PROPERTY)
    static DatabaseTlsGuard databaseTlsGuard(Environment environment) {
        return new DatabaseTlsGuard(environment);
    }
}
