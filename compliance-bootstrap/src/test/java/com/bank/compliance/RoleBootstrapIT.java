package com.bank.compliance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * db/bootstrap/roles.sql, the DBA bootstrap of each environment, on a scratch
 * database: run twice (the second run changes nothing), then every migration
 * as the owner it creates, then the runtime role's privileges as V7 and V12
 * leave them. Evidence stays insert-only for the runtime role: UPDATE, DELETE
 * and TRUNCATE on compliance_screening are refused by privilege, before the V4
 * trigger is reached.
 *
 * <p>Needs a superuser connection (the CI service container's user): the file
 * creates roles, and the test acts as each role with SET ROLE, so no password
 * is set or needed. Roles that exist before the test (a shared cluster) are
 * left in place afterwards.
 */
class RoleBootstrapIT {

    private static final String OWNER = "compliance_evidence_owner";
    private static final String RUNTIME = "compliance_evidence_app";
    private static final String SCHEMA = "sc_cmp_evidence";
    private static final String SCRATCH_DATABASE = "db_cmp_role_bootstrap_it";

    private static JdbcTemplate admin;
    private static boolean rolesExistedBefore;
    private static String script;

    @BeforeAll
    static void scratchDatabase() throws IOException {
        PostgresTestDatabase.assumeAvailable();
        admin = PostgresTestDatabase.owner();
        assertThat(admin.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean.class))
            .as("the test database user must be a superuser to create roles (CI: the service container's POSTGRES_USER)")
            .isTrue();
        rolesExistedBefore = roleCount() > 0;
        admin.execute("drop database if exists " + SCRATCH_DATABASE);
        admin.execute("create database " + SCRATCH_DATABASE);
        script = Files.readString(bootstrapFile());
    }

    @AfterAll
    static void dropScratchDatabase() {
        if (admin == null) {
            return;
        }
        admin.execute("drop database if exists " + SCRATCH_DATABASE);
        if (!rolesExistedBefore) {
            admin.execute("drop role if exists " + RUNTIME);
            admin.execute("drop role if exists " + OWNER);
        }
    }

    @Test
    void bootstrapsBothRolesIdempotentlyAndTheMigrationsLeaveEvidenceInsertOnlyForTheRuntimeRole() throws SQLException {
        try (SingleConnectionDataSource scratch = scratchConnection()) {
            JdbcTemplate db = new JdbcTemplate(scratch);

            db.execute(script);
            Map<String, Object> afterFirstRun = state(db);
            db.execute(script);
            assertThat(state(db)).as("a second run changes nothing").isEqualTo(afterFirstRun);

            assertThat(db.queryForList("""
                    select rolname from pg_roles
                    where rolname in (?, ?) and rolcanlogin and not rolsuper and not rolcreaterole
                      and not rolcreatedb and not rolreplication and not rolbypassrls
                    order by rolname""", String.class, OWNER, RUNTIME))
                .as("two distinct LOGIN roles without elevated attributes").containsExactly(RUNTIME, OWNER);
            assertThat(db.queryForObject("select rolpassword is null from pg_authid where rolname = ?", Boolean.class, OWNER))
                .as("no password in the file: it is set out of band").isTrue();
            assertThat(db.queryForObject("select pg_has_role(?, ?, 'MEMBER')", Boolean.class, RUNTIME, OWNER))
                .as("the runtime role is not a member of the owner").isFalse();
            assertThat(db.queryForObject("select nspowner::regrole::text from pg_namespace where nspname = ?",
                String.class, SCHEMA)).isEqualTo(OWNER);
            assertThat(db.queryForObject("select has_schema_privilege(?, ?, 'USAGE')", Boolean.class, RUNTIME, SCHEMA))
                .as("before the migrations the runtime role has nothing in the schema").isFalse();
        }

        Flyway.configure()
            .dataSource(scratchUrl(), PostgresTestDatabase.ownerUser(), PostgresTestDatabase.ownerPassword())
            .initSql("set role " + OWNER)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .placeholders(Map.of("runtime_role", RUNTIME))
            .locations("classpath:db/migration")
            .load()
            .migrate();

        try (SingleConnectionDataSource scratch = scratchConnection()) {
            JdbcTemplate db = new JdbcTemplate(scratch);
            assertThat(db.queryForList("select distinct tableowner from pg_tables where schemaname = ?", String.class, SCHEMA))
                .as("the owner owns every table").containsExactly(OWNER);
            assertThat(db.queryForObject(
                "select count(*) from " + SCHEMA + ".flyway_schema_history where success and installed_by = ?",
                Integer.class, OWNER)).as("Flyway ran as the owner").isPositive();

            db.execute("set role " + RUNTIME);
            assertThat(db.queryForObject("select current_user", String.class)).isEqualTo(RUNTIME);
            assertThat(db.queryForObject("select count(*) from " + SCHEMA + ".compliance_screening", Integer.class)).isZero();
            assertThat(db.queryForObject("select count(*) from " + SCHEMA + ".flyway_schema_history", Integer.class))
                .as("V12: the service validates the history as the runtime role").isPositive();
            for (String statement : List.of(
                    "update " + SCHEMA + ".compliance_screening set decision = decision",
                    "delete from " + SCHEMA + ".compliance_screening",
                    "truncate " + SCHEMA + ".compliance_screening",
                    "delete from " + SCHEMA + ".flyway_schema_history",
                    "alter table " + SCHEMA + ".compliance_screening disable trigger all",
                    "create table " + SCHEMA + ".planted (id int)")) {
                assertThatThrownBy(() -> db.execute(statement))
                    .as(statement)
                    .hasRootCauseInstanceOf(SQLException.class)
                    .rootCause().satisfies(cause -> assertThat(((SQLException) cause).getSQLState())
                        .as("refused by privilege or ownership, not by the trigger: " + statement)
                        .isEqualTo("42501"));
            }
            db.execute("reset role");
        }
    }

    /** Roles, the schema and the privileges the file sets; compared across two runs. */
    private static Map<String, Object> state(JdbcTemplate db) {
        return Map.of(
            "roles", db.queryForList("""
                select rolname, rolcanlogin, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolconnlimit
                from pg_roles where rolname in (?, ?) order by rolname""", OWNER, RUNTIME),
            "members", db.queryForList("""
                select roleid::regrole::text as role, member::regrole::text as member from pg_auth_members
                where roleid in (?::regrole, ?::regrole) or member in (?::regrole, ?::regrole) order by 1, 2""",
                OWNER, RUNTIME, OWNER, RUNTIME),
            "schema", db.queryForList("select nspowner::regrole::text as owner, nspacl::text as acl from pg_namespace"
                + " where nspname = ?", SCHEMA),
            "database", db.queryForList("select datacl::text as acl from pg_database where datname = current_database()"));
    }

    private static int roleCount() {
        Integer count = admin.queryForObject("select count(*) from pg_roles where rolname in (?, ?)", Integer.class,
            OWNER, RUNTIME);
        return count == null ? 0 : count;
    }

    private static SingleConnectionDataSource scratchConnection() {
        return new SingleConnectionDataSource(scratchUrl(), PostgresTestDatabase.ownerUser(),
            PostgresTestDatabase.ownerPassword(), true);
    }

    /** The test database URL with the scratch database in place of the database name. */
    private static String scratchUrl() {
        return PostgresTestDatabase.url().replaceFirst("^(jdbc:postgresql://[^/]+/)[^?]*", "$1" + SCRATCH_DATABASE);
    }

    /** db/bootstrap/roles.sql from the repository root (Gradle runs the tests in the module directory). */
    private static Path bootstrapFile() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("db/bootstrap/roles.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("db/bootstrap/roles.sql not found above " + Path.of("").toAbsolutePath());
    }
}
