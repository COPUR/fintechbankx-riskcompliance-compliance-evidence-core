package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Out-port adapter for {@link ComplianceResultRepository} over the service's
 * own schema (sc_cmp_evidence). The unique transaction_id index makes the
 * first of two concurrent screenings of one transaction win; the other gets a
 * DataIntegrityViolationException and the caller retries to read it.
 */
@Repository
@Transactional
public class JpaComplianceResultRepository implements ComplianceResultRepository {

    private final SpringDataComplianceScreeningRepository screenings;

    public JpaComplianceResultRepository(SpringDataComplianceScreeningRepository screenings) {
        this.screenings = screenings;
    }

    @Override
    public ComplianceResult save(ComplianceResult result) {
        screenings.saveAndFlush(ComplianceScreeningPersistenceMapper.toEntity(result));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComplianceResult> findByTransactionId(String transactionId) {
        return screenings.findByTransactionId(transactionId).map(ComplianceScreeningPersistenceMapper::toDomain);
    }
}
