package com.bank.compliance;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml); locally either set TEST_DB_URL
 * or have Docker running for Testcontainers.
 *
 * Runs the service as in production: Flyway as the migration owner (the
 * database's test user, through DB_MIGRATION_USERNAME / _PASSWORD) and the
 * application as a separate runtime role (DB_USERNAME) that has only the
 * grants V7 gives it. The owner needs CREATEROLE to create that role.
 */
final class PostgresTestDatabase {

    static final String RUNTIME_ROLE = "compliance_runtime_it";
    static final String RUNTIME_PASSWORD = "compliance_runtime_it";

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    private PostgresTestDatabase() {
    }

    /**
     * Call from a static @BeforeAll so the class is skipped, not failed, without
     * a database; CI sets REQUIRE_TEST_DB=true so a missing database fails it.
     */
    static void assumeAvailable() {
        if ("true".equalsIgnoreCase(System.getenv("REQUIRE_TEST_DB")) && !hasExternalDatabase()) {
            throw new IllegalStateException("REQUIRE_TEST_DB is set but TEST_DB_URL is empty");
        }
        Assumptions.assumeTrue(hasExternalDatabase() || DockerClientFactory.instance().isDockerAvailable(),
            "Set TEST_DB_URL or start Docker to run PostgreSQL integration tests");
    }

    private static boolean hasExternalDatabase() {
        String value = System.getenv("TEST_DB_URL");
        return value != null && !value.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", () -> url);
        // Runtime role: what the pods connect as.
        registry.add("DB_USERNAME", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        // Migration owner: what Flyway connects as.
        registry.add("DB_MIGRATION_USERNAME", () -> ownerUser);
        registry.add("DB_MIGRATION_PASSWORD", () -> ownerPassword);
        // In the cluster the migration Job migrates and the service only validates
        // (DatabaseMigrationIT covers both); test contexts keep migrating as the owner.
        registry.add("compliance.database.flyway", () -> "migrate");
    }

    static synchronized String url() {
        start();
        return url;
    }

    static synchronized String ownerUser() {
        start();
        return ownerUser;
    }

    static synchronized String ownerPassword() {
        start();
        return ownerPassword;
    }

    /** The migration owner's connection, for test set-up the runtime role may not do (TRUNCATE). */
    static synchronized JdbcTemplate owner() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
    }

    /** A plain connection as the runtime role, outside the application. */
    static synchronized JdbcTemplate runtime() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static void start() {
        if (url != null) {
            return;
        }
        String external = System.getenv("TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            ownerUser = env("TEST_DB_USERNAME", "compliance_test");
            ownerPassword = env("TEST_DB_PASSWORD", "compliance_test");
        } else {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_cmp_evidence_test")
                .withUsername("compliance_test")
                .withPassword("compliance_test");
            container.start();
            url = container.getJdbcUrl();
            ownerUser = container.getUsername();
            ownerPassword = container.getPassword();
        }
        new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).execute("""
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = '%1$s') then
                    create role %1$s login password '%2$s';
                end if;
            end $$
            """.formatted(RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
