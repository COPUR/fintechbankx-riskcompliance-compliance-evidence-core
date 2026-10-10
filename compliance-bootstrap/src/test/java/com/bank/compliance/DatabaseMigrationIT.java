package com.bank.compliance;

import com.bank.compliance.infrastructure.outbox.OutboxRelay;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deploy split: the chart's pre-install/pre-upgrade Job runs the image with
 * args "migrate" and the schema owner's credential (DB_MIGRATION_*); the
 * service pods get only the runtime role (DB_USERNAME) and validate.
 *
 * <p>The Job is checked on a scratch schema (all migrations, grants, exit code,
 * a context without web server, JPA or Kafka, the TLS guard). The service is
 * checked on sc_cmp_evidence after the Job's run: it starts as the runtime role
 * without changing the schema history, and refuses to start while a migration
 * is pending.
 */
class DatabaseMigrationIT {

    private static final String JOB_SCHEMA = "sc_cmp_evidence_job_it";
    private static final String SERVICE_SCHEMA = "sc_cmp_evidence";
    private static final String RDS_BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";

    private static JdbcTemplate owner;

    @BeforeAll
    static void database() {
        PostgresTestDatabase.assumeAvailable();
        owner = PostgresTestDatabase.owner();
        owner.execute("drop schema if exists " + JOB_SCHEMA + " cascade");
    }

    @AfterAll
    static void dropScratchSchema() {
        if (owner != null) {
            owner.execute("drop schema if exists " + JOB_SCHEMA + " cascade");
        }
    }

    @Test
    void theMigrationJobAppliesEveryMigrationAsTheOwnerWithoutStartingTheService() throws IOException {
        try (ConfigurableApplicationContext job = DatabaseMigration.start(job(JOB_SCHEMA))) {
            assertThat(job).as("no web server in the Job").isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(job.getBeanNamesForType(EntityManagerFactory.class)).as("no JPA").isEmpty();
            assertThat(job.getBeanNamesForType(KafkaTemplate.class)).as("no Kafka").isEmpty();
            assertThat(job.getBeanNamesForType(OutboxRelay.class)).as("no outbox relay").isEmpty();
        }

        assertThat(appliedVersions(JOB_SCHEMA)).isEqualTo(resolvedMigrations());
        String runtime = PostgresTestDatabase.RUNTIME_ROLE;
        assertThat(privilege(runtime, JOB_SCHEMA + ".compliance_screening", "INSERT")).isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".compliance_screening", "UPDATE"))
            .as("evidence stays insert-only for the runtime role").isFalse();
        assertThat(privilege(runtime, JOB_SCHEMA + ".flyway_schema_history", "SELECT"))
            .as("the service validates as the runtime role").isTrue();
        assertThat(privilege(runtime, JOB_SCHEMA + ".flyway_schema_history", "INSERT"))
            .as("but cannot record a migration").isFalse();
    }

    @Test
    void theMigrationJobExitsWithZeroAndARerunChangesNothing() {
        assertThat(DatabaseMigration.run(job(JOB_SCHEMA))).isZero();
        int applied = appliedVersions(JOB_SCHEMA);

        assertThat(DatabaseMigration.run(job(JOB_SCHEMA))).isZero();

        assertThat(appliedVersions(JOB_SCHEMA)).isEqualTo(applied);
    }

    /** The Job gets DB_SSL_ROOT_CERT from the chart like the pods, so DatabaseTlsGuard runs there too. */
    @Test
    void theMigrationJobRefusesADatabaseUrlTheDriverWouldNotVerify() {
        String[] arguments = with(job(JOB_SCHEMA), "--DB_SSL_ROOT_CERT=" + RDS_BUNDLE);

        // Thrown by the guard itself (a BeanFactoryPostProcessor), before any connection or Flyway bean.
        assertThatThrownBy(() -> DatabaseMigration.start(arguments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url must use sslmode=verify-full");
        assertThat(DatabaseMigration.run(arguments)).isNotZero();
    }

    @Test
    void theServiceStartsAsTheRuntimeRoleAndLeavesTheSchemaHistoryAlone() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();
        int applied = appliedVersions(SERVICE_SCHEMA);

        try (ConfigurableApplicationContext service = service()) {
            assertThat(service.isRunning()).isTrue();
        }

        assertThat(appliedVersions(SERVICE_SCHEMA)).isEqualTo(applied);
    }

    @Test
    void theServiceRefusesToStartWhileAMigrationIsPending() {
        assertThat(DatabaseMigration.run(job(SERVICE_SCHEMA))).isZero();

        assertThatThrownBy(() -> service("--spring.flyway.locations=classpath:db/migration,classpath:db/pending-it"))
            .rootCause().isInstanceOf(FlywayValidateException.class)
            .hasMessageContaining("Detected resolved migration not applied to database: 9999");
        assertThat(owner.queryForObject(
            "select count(*) from " + SERVICE_SCHEMA + ".flyway_schema_history where version = '9999'", Integer.class))
            .isZero();
    }

    /** What the Job's pod gets: DB_URL, DB_USERNAME (the role V7 grants) and the db-migration secret. */
    private static String[] job(String schema) {
        return new String[] {
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword(),
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
        };
    }

    /** What a service pod gets: the runtime role only, no DB_MIGRATION_*, chart-default Flyway mode. */
    private static ConfigurableApplicationContext service(String... extra) {
        String[] arguments = with(new String[] {
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--spring.datasource.password=" + PostgresTestDatabase.RUNTIME_PASSWORD,
            "--server.port=0",
            "--management.server.port=0",
            "--compliance.outbox.relay.enabled=false",
        }, extra);
        return new SpringApplicationBuilder(ComplianceEvidenceApplication.class).run(arguments);
    }

    private static String[] with(String[] arguments, String... extra) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    private static int appliedVersions(String schema) {
        return owner.queryForObject("select count(*) from " + schema
            + ".flyway_schema_history where success and version is not null", Integer.class);
    }

    private static int resolvedMigrations() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql").length;
    }

    private static boolean privilege(String role, String table, String privilege) {
        return owner.queryForObject("select has_table_privilege(?, ?, ?)", Boolean.class, role, table, privilege);
    }
}
