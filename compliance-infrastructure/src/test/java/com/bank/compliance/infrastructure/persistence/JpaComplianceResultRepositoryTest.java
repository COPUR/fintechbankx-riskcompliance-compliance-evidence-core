package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.domain.ScreeningAlreadyRecordedException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaComplianceResultRepositoryTest {

    private final SpringDataComplianceScreeningRepository screenings = mock(SpringDataComplianceScreeningRepository.class);
    private final JpaComplianceResultRepository repository = new JpaComplianceResultRepository(screenings);
    private final ComplianceResult result =
            ComplianceResultFixtures.result("TX-JPA-1", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("insert failed", new SQLException("dup", "23505"), constraint));
    }

    @Test
    void theTransactionIdUniqueConflictBecomesADomainException() {
        when(screenings.saveAndFlush(any())).thenThrow(violation("uq_compliance_screening_transaction"));

        assertThatThrownBy(() -> repository.save(result))
                .isInstanceOf(ScreeningAlreadyRecordedException.class)
                .hasMessageContaining("TX-JPA-1");
    }

    @Test
    void anyOtherConstraintIsRethrownUnchanged() {
        DataIntegrityViolationException other = violation("ck_compliance_screening_currency");
        when(screenings.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> repository.save(result)).isSameAs(other);
    }

    @Test
    void constraintIsAlsoRecognisedFromTheDriverMessage() {
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_compliance_screening_transaction\"", "23505");

        assertThat(JpaComplianceResultRepository.isTransactionIdConflict(new DataIntegrityViolationException("x", driver))).isTrue();
        assertThat(JpaComplianceResultRepository.isTransactionIdConflict(
                new DataIntegrityViolationException("x", new SQLException("ERROR: other", "23505")))).isFalse();
        assertThat(JpaComplianceResultRepository.isTransactionIdConflict(
                new DataIntegrityViolationException("x", new SQLException("uq_compliance_screening_transaction", "23514")))).isFalse();
        assertThat(JpaComplianceResultRepository.isTransactionIdConflict(new DataIntegrityViolationException("no cause"))).isFalse();
    }

    @Test
    void savedResultIsReturned() {
        assertThat(repository.save(result)).isSameAs(result);
    }
}
