package com.bank.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single-user fallback (decision 0002): the migration role and the runtime
 * role must be two roles whenever both are known. Without DB_MIGRATION_USERNAME
 * (service pods, local runs) the guard has nothing to compare.
 */
class MigrationRoleGuardTest {

    private static final String REFUSED = "DB_MIGRATION_USERNAME must not be the runtime role DB_USERNAME";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FlywayStartupConfiguration.class);

    @Test
    void refusesTheSameRoleForMigrationAndRuntime() {
        assertThatThrownBy(() -> guard("compliance_evidence_app", "compliance_evidence_app").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(REFUSED)
                .message().doesNotContain("compliance_evidence_app");
    }

    /** PostgreSQL folds unquoted role names to lower case, so a case variant is the same role. */
    @Test
    void refusesTheSameRoleInAnotherCaseOrWithSpaces() {
        assertThatThrownBy(() -> guard("Compliance_Evidence_App", "compliance_evidence_app").verify())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(REFUSED);
        assertThatThrownBy(() -> guard(" compliance_evidence_app ", "compliance_evidence_app").verify())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(REFUSED);
    }

    @Test
    void acceptsTwoRoles() {
        assertThatCode(() -> guard("compliance_evidence_owner", "compliance_evidence_app").verify())
                .doesNotThrowAnyException();
    }

    @Test
    void checksNothingUnlessBothRolesAreKnown() {
        assertThatCode(() -> guard(null, "compliance_evidence_app").verify()).doesNotThrowAnyException();
        assertThatCode(() -> guard("compliance_evidence_owner", null).verify()).doesNotThrowAnyException();
        assertThatCode(() -> guard("", "").verify()).doesNotThrowAnyException();
        assertThatCode(() -> guard(null, null).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartTheContextBeforeAnyOtherBeanWhenBothRolesAreTheSame() {
        contextRunner
                .withPropertyValues("DB_MIGRATION_USERNAME=compliance_evidence_app", "DB_USERNAME=compliance_evidence_app")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining(REFUSED));
        contextRunner
                .withPropertyValues("DB_MIGRATION_USERNAME=compliance_evidence_owner", "DB_USERNAME=compliance_evidence_app")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(MigrationRoleGuard.class));
    }

    private static MigrationRoleGuard guard(String migrationUser, String runtimeUser) {
        MockEnvironment environment = new MockEnvironment();
        if (migrationUser != null) {
            environment.setProperty(MigrationRoleGuard.MIGRATION_USERNAME, migrationUser);
        }
        if (runtimeUser != null) {
            environment.setProperty(MigrationRoleGuard.RUNTIME_USERNAME, runtimeUser);
        }
        return new MigrationRoleGuard(environment);
    }
}
