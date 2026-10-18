package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceScreeningPersistenceMapperTest {

    @Test
    void roundTripKeepsTheDecisionOfRecord() {
        ComplianceResult screened = ComplianceResultFixtures.result("TX-MAP-1", "C-9", ComplianceDecision.FAIL,
                List.of("SANCTIONS_HIT", "KYC_NOT_VERIFIED"));

        ComplianceScreeningJpaEntity row = ComplianceScreeningPersistenceMapper.toEntity(screened);
        ComplianceResult loaded = ComplianceScreeningPersistenceMapper.toDomain(row);

        assertThat(row.getDecision()).isEqualTo("FAIL");
        assertThat(loaded.getId()).isEqualTo(screened.getId());
        assertThat(loaded.getTransactionId()).isEqualTo("TX-MAP-1");
        assertThat(loaded.getCustomerId()).isEqualTo("C-9");
        assertThat(loaded.getReasons()).containsExactly("SANCTIONS_HIT", "KYC_NOT_VERIFIED");
        assertThat(loaded.getCheckedAt()).isEqualTo(screened.getCheckedAt());
        assertThat(row.getAmount()).isEqualByComparingTo("100.00");
        assertThat(row.getCurrency()).isEqualTo("AED");
        assertThat(row.isSanctionsHit()).isFalse();
        assertThat(row.isKycVerified()).isTrue();
        assertThat(row.isPep()).isFalse();
        assertThat(row.getAttestationSource()).isEqualTo("CALLER_ATTESTED");
        assertThat(row.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
        assertThat(loaded.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
        assertThat(loaded.getFacts()).isEqualTo(screened.getFacts());
        assertThat(loaded.getRuleSetVersion()).isEqualTo(screened.getRuleSetVersion());
        assertThat(row.getListVersions()).isEmpty();
        assertThat(loaded.getListVersions()).isEmpty();
    }

    /** A computed screening stores which list loads it was matched against, as a JSON array on the row. */
    @Test
    void roundTripKeepsTheListVersionsOfAComputedScreening() {
        java.time.Instant loadedAt = java.time.Instant.parse("2026-10-18T01:00:00.123456Z");
        List<com.bank.compliance.domain.ListVersion> lists = List.of(
                new com.bank.compliance.domain.ListVersion(com.bank.compliance.domain.ScreeningList.SANCTIONS, "UN", "2026-10-17", loadedAt),
                new com.bank.compliance.domain.ListVersion(com.bank.compliance.domain.ScreeningList.PEP, "UAE-LOCAL", "2026-10", loadedAt.minusSeconds(60)));
        ComplianceResult screened = ComplianceResult.create("TX-MAP-2", "C-9",
                new com.bank.compliance.domain.ScreeningFacts(new java.math.BigDecimal("12000.00"), "USD", true, true, false,
                        com.bank.compliance.domain.Attestation.computed("svc-pay-initiation-settlement")),
                ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"), com.bank.compliance.domain.service.ComplianceRuleService.RULE_SET_VERSION, lists);

        ComplianceScreeningJpaEntity row = ComplianceScreeningPersistenceMapper.toEntity(screened);
        ComplianceResult loaded = ComplianceScreeningPersistenceMapper.toDomain(row);

        assertThat(row.getAttestationSource()).isEqualTo("COMPUTED");
        assertThat(row.getListVersions()).hasSize(2);
        assertThat(row.getListVersions().getFirst())
                .containsEntry("list", "SANCTIONS").containsEntry("source", "UN")
                .containsEntry("version", "2026-10-17").containsEntry("loadedAt", "2026-10-18T01:00:00.123456Z");
        assertThat(loaded.getListVersions()).containsExactlyElementsOf(lists);
        assertThat(loaded.getAttestation()).isEqualTo(com.bank.compliance.domain.AttestationSource.COMPUTED);
    }

    @Test
    void aRowWithoutTheCallerIsRejectedNotMappedToASentinel() {
        ComplianceScreeningJpaEntity broken = new ComplianceScreeningJpaEntity("CMP-OLD", "TX-OLD", "C-1",
                new java.math.BigDecimal("100.00"), "AED", false, true, false, "CALLER_ATTESTED", null, "PASS",
                List.of("COMPLIANT"), List.of(), "cmp-screening-rules-v1", java.time.Instant.parse("2026-10-01T00:00:00Z"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ComplianceScreeningPersistenceMapper.toDomain(broken))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
