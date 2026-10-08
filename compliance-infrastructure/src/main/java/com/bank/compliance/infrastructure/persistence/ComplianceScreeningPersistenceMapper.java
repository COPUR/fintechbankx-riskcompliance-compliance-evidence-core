package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.AttestationSource;
import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultId;
import com.bank.compliance.domain.ComplianceResultSnapshot;
import com.bank.compliance.domain.ScreeningFacts;

import java.util.List;

final class ComplianceScreeningPersistenceMapper {

    private ComplianceScreeningPersistenceMapper() {
    }

    static ComplianceScreeningJpaEntity toEntity(ComplianceResult result) {
        ScreeningFacts facts = result.getFacts();
        return new ComplianceScreeningJpaEntity(
                result.getId().getValue(),
                result.getTransactionId(),
                result.getCustomerId(),
                facts.amount(),
                facts.currency(),
                facts.sanctionsHit(),
                facts.kycVerified(),
                facts.pep(),
                facts.attestation().source().name(),
                facts.attestation().attestedBy(),
                result.getDecision().name(),
                List.copyOf(result.getReasons()),
                result.getRuleSetVersion(),
                result.getCheckedAt());
    }

    static ComplianceResult toDomain(ComplianceScreeningJpaEntity row) {
        return ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of(row.getScreeningId()),
                row.getTransactionId(),
                row.getCustomerId(),
                new ScreeningFacts(row.getAmount(), row.getCurrency(), row.isSanctionsHit(), row.isKycVerified(),
                        row.isPep(), new Attestation(AttestationSource.valueOf(row.getAttestationSource()),
                                row.getAttestedBy())),
                ComplianceDecision.valueOf(row.getDecision()),
                row.getReasons() == null ? List.of() : row.getReasons(),
                row.getRuleSetVersion(),
                row.getCheckedAt()));
    }
}
