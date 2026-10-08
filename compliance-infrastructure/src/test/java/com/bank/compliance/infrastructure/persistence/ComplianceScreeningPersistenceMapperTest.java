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
    }
}
