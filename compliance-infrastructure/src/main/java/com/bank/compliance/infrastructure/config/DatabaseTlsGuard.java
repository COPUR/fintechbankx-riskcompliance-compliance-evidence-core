package com.bank.compliance.infrastructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.postgresql.Driver;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Aurora TLS at startup (cicd-templates 4f0f266). The chart checks config.DB_URL
 * when it renders, but the service can also get its database URL from values
 * the chart never sees. Before any connection pool or Flyway bean exists, this
 * guard reads every URL the service would connect with exactly as PgJDBC reads
 * it ({@link Driver#parseURL}: the last repeated parameter wins, names and
 * values are percent-decoded) and refuses to start unless the driver will
 * verify Aurora's certificate and host name against the mounted RDS CA bundle:
 * sslmode=verify-full, sslrootcert equal to DB_SSL_ROOT_CERT, and no sslfactory,
 * sslhostnameverifier or sslpasswordcallback that would replace the check.
 * spring.datasource.hikari.jdbc-url, which replaces spring.datasource.url in
 * the pool, must also equal it: a verified URL to another database is still
 * another database.
 *
 * <p>Registered by {@link DatabaseTlsConfiguration} only when DB_SSL_ROOT_CERT is
 * set. Messages name the property, never the URL, which may carry a password.
 */
public final class DatabaseTlsGuard implements BeanFactoryPostProcessor {

    static final String ROOT_CERT_PROPERTY = "DB_SSL_ROOT_CERT";

    private static final String DATASOURCE_URL = "spring.datasource.url";
    private static final String HIKARI_URL = "spring.datasource.hikari.jdbc-url";
    private static final List<String> OPTIONAL_URLS = List.of("spring.flyway.url", HIKARI_URL);
    private static final String DRIVER_PROPERTIES = "spring.datasource.hikari.data-source-properties";
    private static final List<String> VERIFICATION_OVERRIDES = List.of(
            "sslfactory", "sslhostnameverifier", "sslpasswordcallback");

    private final Environment environment;

    DatabaseTlsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify();
    }

    void verify() {
        String rootCert = environment.getProperty(ROOT_CERT_PROPERTY, "");
        if (rootCert.isBlank()) {
            throw new IllegalStateException(ROOT_CERT_PROPERTY + " is set but empty: it must name the mounted RDS CA bundle");
        }
        String datasourceUrl = environment.getProperty(DATASOURCE_URL);
        requireVerifiedTls(DATASOURCE_URL, datasourceUrl, rootCert);
        for (String property : OPTIONAL_URLS) {
            String url = environment.getProperty(property);
            if (url != null) {
                requireVerifiedTls(property, url, rootCert);
            }
        }
        String hikariUrl = environment.getProperty(HIKARI_URL);
        if (hikariUrl != null && !hikariUrl.equals(datasourceUrl)) {
            throw new IllegalStateException(HIKARI_URL + " must equal " + DATASOURCE_URL
                    + ": the pool connects with it instead of the checked URL");
        }
        refuseSslDriverProperties();
    }

    private static void requireVerifiedTls(String property, String url, String rootCert) {
        Properties parsed = url == null ? null : Driver.parseURL(url, null);
        if (parsed == null) {
            throw new IllegalStateException(property + " must be a jdbc:postgresql URL with sslmode=verify-full"
                    + " and sslrootcert=" + rootCert);
        }
        if (!"verify-full".equals(parsed.getProperty("sslmode"))) {
            throw new IllegalStateException(property + " must use sslmode=verify-full (with sslrootcert="
                    + rootCert + "), exactly once");
        }
        if (!rootCert.equals(parsed.getProperty("sslrootcert"))) {
            throw new IllegalStateException(property + " must use sslrootcert=" + rootCert
                    + " (" + ROOT_CERT_PROPERTY + "), exactly once");
        }
        for (String override : VERIFICATION_OVERRIDES) {
            if (parsed.getProperty(override) != null) {
                throw new IllegalStateException(property + " must not set " + override
                        + ": it replaces certificate or host name verification");
            }
        }
    }

    /** Driver properties set outside the URL; sslfactory there would never show up in it. */
    private void refuseSslDriverProperties() {
        Map<String, String> properties = Binder.get(environment)
                .bind(DRIVER_PROPERTIES, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        for (String name : properties.keySet()) {
            if (name.toLowerCase(Locale.ROOT).startsWith("ssl")) {
                throw new IllegalStateException(DRIVER_PROPERTIES + " must not set " + name
                        + ": TLS settings belong in spring.datasource.url");
            }
        }
    }
}
