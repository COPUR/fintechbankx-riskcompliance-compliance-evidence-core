package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.AttestationSource;
import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultId;
import com.bank.compliance.domain.ComplianceResultSnapshot;
import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.ScreeningFacts;
import com.bank.compliance.domain.ScreeningList;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                result.getListVersions().stream().map(ComplianceScreeningPersistenceMapper::toJson).toList(),
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
                row.getCheckedAt(),
                row.getListVersions() == null ? List.of()
                        : row.getListVersions().stream().map(ComplianceScreeningPersistenceMapper::fromJson).toList()));
    }

    /** One list load as stored in compliance_screening.list_versions: {list, source, version, loadedAt (ISO-8601 UTC)}. */
    private static Map<String, String> toJson(ListVersion version) {
        Map<String, String> json = new LinkedHashMap<>();
        json.put("list", version.list().name());
        json.put("source", version.source());
        json.put("version", version.version());
        json.put("loadedAt", version.loadedAt().toString());
        return json;
    }

    private static ListVersion fromJson(Map<String, String> json) {
        return new ListVersion(ScreeningList.valueOf(json.get("list")), json.get("source"), json.get("version"),
                Instant.parse(json.get("loadedAt")));
    }
}
