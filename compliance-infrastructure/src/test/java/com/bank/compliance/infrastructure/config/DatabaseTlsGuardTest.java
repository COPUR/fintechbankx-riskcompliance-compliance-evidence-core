package com.bank.compliance.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup half of the Aurora TLS contract (cicd-templates 4f0f266): the
 * chart's render check can be bypassed by values it never sees, so the service
 * refuses to start unless the driver itself would verify the certificate and
 * host name against the mounted RDS CA bundle.
 */
class DatabaseTlsGuardTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String BASE = "jdbc:postgresql://aurora.example:5432/db_cmp_evidence_dev";
    private static final String VALID = BASE + "?sslmode=verify-full&sslrootcert=" + BUNDLE;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseTlsConfiguration.class);

    @Test
    void acceptsVerifyFullAgainstTheMountedBundle() {
        assertThatCode(() -> guard(VALID).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesARepeatedSslmodeBecauseTheDriverKeepsTheLastOne() {
        assertThatThrownBy(() -> guard(VALID + "&sslmode=disable").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslmode=verify-full");
    }

    @Test
    void refusesADecoyRootCertificate() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=verify-full&sslrootcert=/tmp/decoy.pem").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslrootcert=" + BUNDLE);
    }

    @Test
    void refusesANonValidatingSslFactory() {
        assertThatThrownBy(() -> guard(VALID + "&sslfactory=org.postgresql.ssl.NonValidatingFactory").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must not set sslfactory");
    }

    @Test
    void refusesVerifyFullHiddenInAnotherParameterValue() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=require&applicationName=sslmode=verify-full").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslmode=verify-full");
    }

    @Test
    void readsPercentEncodedValuesDecodedAsTheDriverDoes() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=verify-full&sslrootcert=%2Ftmp%2Fdecoy.pem").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslrootcert=" + BUNDLE);
    }

    @Test
    void refusesAFlywayUrlWithoutVerification() {
        MockEnvironment environment = environment(VALID).withProperty("spring.flyway.url", BASE + "?sslmode=disable");

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.url must use sslmode=verify-full");
    }

    @Test
    void acceptsAVerifiedFlywayUrl() {
        MockEnvironment environment = environment(VALID).withProperty("spring.flyway.url", VALID);

        assertThatCode(() -> new DatabaseTlsGuard(environment).verify()).doesNotThrowAnyException();
    }

    /** Flyway connects with spring.flyway.url instead of the datasource URL: a verified URL elsewhere migrates another database. */
    @Test
    void refusesAFlywayUrlThatDiffersFromTheDatasourceUrlEvenWhenVerified() {
        String elsewhere = "jdbc:postgresql://other.example:5432/db_other?sslmode=verify-full&sslrootcert=" + BUNDLE;
        MockEnvironment environment = environment(VALID).withProperty("spring.flyway.url", elsewhere);

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.url must equal spring.datasource.url")
                .message().doesNotContain("other.example");
    }

    @Test
    void refusesToStartTheContextWhenTheFlywayUrlPointsElsewhere() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "spring.datasource.url=" + VALID,
                        "spring.flyway.url=jdbc:postgresql://other.example:5432/db_other"
                                + "?sslmode=verify-full&sslrootcert=" + BUNDLE)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("spring.flyway.url must equal spring.datasource.url"));
    }

    @Test
    void refusesAHikariJdbcUrlThatWouldReplaceTheDatasourceUrl() {
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.datasource.hikari.jdbc-url", BASE + "?sslmode=disable");

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.hikari.jdbc-url must use sslmode=verify-full");
    }

    /** A verified URL to another database is still another database: the pool must connect with the checked one. */
    @Test
    void refusesAHikariJdbcUrlThatDiffersFromTheDatasourceUrlEvenWhenVerified() {
        String elsewhere = "jdbc:postgresql://other.example:5432/db_other?sslmode=verify-full&sslrootcert=" + BUNDLE;
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.datasource.hikari.jdbc-url", elsewhere);

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.hikari.jdbc-url must equal spring.datasource.url")
                .message().doesNotContain("other.example");
    }

    @Test
    void acceptsAHikariJdbcUrlEqualToTheDatasourceUrl() {
        MockEnvironment environment = environment(VALID).withProperty("spring.datasource.hikari.jdbc-url", VALID);

        assertThatCode(() -> new DatabaseTlsGuard(environment).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartTheContextWhenTheHikariJdbcUrlPointsElsewhere() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "spring.datasource.url=" + VALID,
                        "spring.datasource.hikari.jdbc-url=jdbc:postgresql://other.example:5432/db_other"
                                + "?sslmode=verify-full&sslrootcert=" + BUNDLE)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("spring.datasource.hikari.jdbc-url must equal spring.datasource.url"));
    }

    @Test
    void refusesSslDriverPropertiesOutsideTheUrl() {
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.datasource.hikari.data-source-properties.sslfactory",
                        "org.postgresql.ssl.NonValidatingFactory");

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.hikari.data-source-properties must not set sslfactory");
    }

    @Test
    void refusesAUrlThatIsNotPostgres() {
        assertThatThrownBy(() -> guard("jdbc:h2:mem:compliance").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must be a jdbc:postgresql URL");
    }

    @Test
    void neverEchoesTheUrlBecauseItMayCarryAPassword() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=disable&password=not-for-logs").verify())
                .isInstanceOf(IllegalStateException.class)
                .message().doesNotContain("not-for-logs");
    }

    @Test
    void refusesToStartTheContextWithAnUnverifiedUrlWhenTheBundleIsMounted() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE,
                        "spring.datasource.url=" + BASE + "?sslmode=require&applicationName=sslmode=verify-full")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("spring.datasource.url must use sslmode=verify-full"));
    }

    @Test
    void startsTheContextWithAVerifiedUrlWhenTheBundleIsMounted() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "spring.datasource.url=" + VALID)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DatabaseTlsGuard.class));
    }

    @Test
    void staysInactiveWithoutTheBundleSoLocalRunsAndTestsStart() {
        contextRunner
                .withPropertyValues("spring.datasource.url=" + BASE + "?sslmode=disable")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseTlsGuard.class));
    }

    private static DatabaseTlsGuard guard(String url) {
        return new DatabaseTlsGuard(environment(url));
    }

    private static MockEnvironment environment(String url) {
        return new MockEnvironment()
                .withProperty("DB_SSL_ROOT_CERT", BUNDLE)
                .withProperty("spring.datasource.url", url);
    }
}
