package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ScreeningAlreadyRecordedException;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Out-port adapter for {@link ComplianceResultRepository} over the service's
 * own schema (sc_cmp_evidence). The unique transaction_id index makes the
 * first of two concurrent screenings of one transaction win; the other's
 * insert is translated to {@link ScreeningAlreadyRecordedException} and its
 * transaction rolls back. Any other integrity failure is a defect and is
 * rethrown unchanged.
 */
@Repository
@Transactional
public class JpaComplianceResultRepository implements ComplianceResultRepository {

    static final String UNIQUE_TRANSACTION_CONSTRAINT = "uq_compliance_screening_transaction";
    private static final String UNIQUE_VIOLATION = "23505";

    private final SpringDataComplianceScreeningRepository screenings;

    public JpaComplianceResultRepository(SpringDataComplianceScreeningRepository screenings) {
        this.screenings = screenings;
    }

    @Override
    public ComplianceResult save(ComplianceResult result) {
        try {
            screenings.saveAndFlush(ComplianceScreeningPersistenceMapper.toEntity(result));
        } catch (DataIntegrityViolationException e) {
            if (isTransactionIdConflict(e)) {
                throw new ScreeningAlreadyRecordedException(result.getTransactionId(), e);
            }
            throw e;
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComplianceResult> findByTransactionId(String transactionId) {
        return screenings.findByTransactionId(transactionId).map(ComplianceScreeningPersistenceMapper::toDomain);
    }

    /** True only for a unique violation of the transaction_id constraint. */
    static boolean isTransactionIdConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return UNIQUE_TRANSACTION_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
            }
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains(UNIQUE_TRANSACTION_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
