package com.bank.compliance;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml); locally either set TEST_DB_URL
 * or have Docker running for Testcontainers.
 */
final class PostgresTestDatabase {

    private static PostgreSQLContainer<?> container;

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
        String url = System.getenv("TEST_DB_URL");
        return url != null && !url.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "compliance_test"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "compliance_test"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_cmp_evidence_test")
                .withUsername("compliance_test")
                .withPassword("compliance_test");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
