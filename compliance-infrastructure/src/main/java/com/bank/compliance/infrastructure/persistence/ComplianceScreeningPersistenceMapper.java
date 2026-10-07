package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultId;
import com.bank.compliance.domain.ComplianceResultSnapshot;

import java.util.List;

final class ComplianceScreeningPersistenceMapper {

    private ComplianceScreeningPersistenceMapper() {
    }

    static ComplianceScreeningJpaEntity toEntity(ComplianceResult result) {
        return new ComplianceScreeningJpaEntity(
                result.getId().getValue(),
                result.getTransactionId(),
                result.getCustomerId(),
                result.getDecision().name(),
                List.copyOf(result.getReasons()),
                result.getCheckedAt());
    }

    static ComplianceResult toDomain(ComplianceScreeningJpaEntity row) {
        return ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of(row.getScreeningId()),
                row.getTransactionId(),
                row.getCustomerId(),
                ComplianceDecision.valueOf(row.getDecision()),
                row.getReasons() == null ? List.of() : row.getReasons(),
                row.getCheckedAt()));
    }
}
