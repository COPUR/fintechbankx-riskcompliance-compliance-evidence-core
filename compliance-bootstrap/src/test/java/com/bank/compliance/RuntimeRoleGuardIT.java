package com.bank.compliance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Each privilege the runtime role must not have, one scratch role at a time:
 * every scratch role gets what V7 gives the runtime role (USAGE on the schema,
 * SELECT and INSERT on the evidence) plus the one thing under test, and the
 * guard must name that finding and nothing identifying. The proper V7/V12
 * runtime role passes. Runs on sc_cmp_evidence after the migration Job.
 */
class RuntimeRoleGuardIT {

    private static final String SCHEMA = "sc_cmp_evidence";
    private static final String EVIDENCE = SCHEMA + ".compliance_screening";
    private static final String SCRATCH = "compliance_scratch_role_it";
    private static final String SCRATCH_PASSWORD = "compliance_scratch_role_it";
    private static final String REFUSED = "DB_USERNAME (the runtime role the service connects as) must be least privilege";

    private static JdbcTemplate owner;
    private static String tableOwner;

    @BeforeAll
    static void migratedDatabase() {
        PostgresTestDatabase.assumeAvailable();
        owner = PostgresTestDatabase.owner();
        assertThat(DatabaseMigration.run(
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword(),
            "--fintechbankx.tls.enforce=false")).isZero();
        tableOwner = owner.queryForObject(
            "select tableowner from pg_tables where schemaname = ? and tablename = 'compliance_screening'",
            String.class, SCHEMA);
    }

    @AfterEach
    void dropScratchRole() {
        owner.execute("""
            do $$
            begin
                if exists (select 1 from pg_roles where rolname = '%1$s') then
                    execute 'drop owned by %1$s cascade';
                    execute 'drop role %1$s';
                end if;
            end $$
            """.formatted(SCRATCH));
    }

    @Test
    void theV7AndV12RuntimeRolePasses() {
        assertThatCode(() -> guard(PostgresTestDatabase.RUNTIME_ROLE, PostgresTestDatabase.RUNTIME_PASSWORD).verify())
            .doesNotThrowAnyException();
    }

    @Test
    void refusesAMemberOfTheSchemaOwner() {
        scratchRoleLikeV7();
        owner.execute("grant " + tableOwner + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("member of the owner")
            .hasMessageNotContaining(SCRATCH)
            .hasMessageNotContaining(tableOwner)
            .hasMessageNotContaining(SCRATCH_PASSWORD);
    }

    @Test
    void refusesARoleThatOwnsATableInTheSchema() {
        scratchRoleLikeV7();
        owner.execute("grant create on schema " + SCHEMA + " to " + SCRATCH);
        owner.execute("set role " + SCRATCH + "; create table " + SCHEMA + ".planted_it (id int); reset role");
        owner.execute("revoke create on schema " + SCHEMA + " from " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("owns a table in the schema")
            .hasMessageNotContaining("CREATE on the schema");
    }

    @Test
    void refusesCreateOnTheSchema() {
        scratchRoleLikeV7();
        owner.execute("grant create on schema " + SCHEMA + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("it has CREATE on the schema");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE", "TRUNCATE", "TRIGGER"})
    void refusesAWritePrivilegeOnTheEvidence(String privilege) {
        scratchRoleLikeV7();
        owner.execute("grant " + privilege + " on " + EVIDENCE + " to " + SCRATCH);

        assertThatThrownBy(() -> scratchGuard().verify())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(REFUSED)
            .hasMessageContaining("it has " + privilege + " on compliance_screening");
    }

    /** V7's grants alone pass: the scratch shape is least privilege until a test adds one thing. */
    @Test
    void aRoleWithOnlyTheV7GrantsPasses() {
        scratchRoleLikeV7();

        assertThatCode(() -> scratchGuard().verify()).doesNotThrowAnyException();
    }

    private static void scratchRoleLikeV7() {
        owner.execute("drop role if exists " + SCRATCH);
        owner.execute("create role " + SCRATCH + " login password '" + SCRATCH_PASSWORD + "'");
        owner.execute("grant usage on schema " + SCHEMA + " to " + SCRATCH);
        owner.execute("grant select, insert on " + EVIDENCE + " to " + SCRATCH);
        owner.execute("grant select on " + SCHEMA + ".flyway_schema_history to " + SCRATCH);
    }

    private static RuntimeRoleGuard scratchGuard() {
        return guard(SCRATCH, SCRATCH_PASSWORD);
    }

    private static RuntimeRoleGuard guard(String role, String password) {
        return new RuntimeRoleGuard(new DriverManagerDataSource(PostgresTestDatabase.url(), role, password), SCHEMA);
    }
}
