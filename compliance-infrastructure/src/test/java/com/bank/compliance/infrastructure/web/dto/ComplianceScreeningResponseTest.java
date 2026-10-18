package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceScreeningResponseTest {

    @Test
    void shouldMapFromDomain() {
        ComplianceResult result = ComplianceResultFixtures.result("TX-1", "C-1", ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"));

        ComplianceScreeningResponse response = ComplianceScreeningResponse.from(result);

        assertThat(response.screeningId()).isEqualTo(result.getId().getValue());
        assertThat(response.transactionId()).isEqualTo("TX-1");
        assertThat(response.decision()).isEqualTo("REVIEW");
        assertThat(response.attestation()).isEqualTo("CALLER_ATTESTED");
        assertThat(response.attestedBy()).isEqualTo("svc-pay-initiation-settlement");
        assertThat(response.listVersions()).isEmpty();
    }

    @Test
    void aComputedResultCarriesTheListVersionsItWasMatchedAgainst() {
        java.time.Instant loaded = java.time.Instant.parse("2026-10-18T01:00:00Z");
        ComplianceResult result = ComplianceResult.create("TX-2", "C-1",
                new com.bank.compliance.domain.ScreeningFacts(new java.math.BigDecimal("10.00"), "USD", false, true, false,
                        com.bank.compliance.domain.Attestation.computed("svc-pay-initiation-settlement")),
                ComplianceDecision.PASS, List.of("COMPLIANT"), com.bank.compliance.domain.service.ComplianceRuleService.RULE_SET_VERSION,
                List.of(new com.bank.compliance.domain.ListVersion(com.bank.compliance.domain.ScreeningList.SANCTIONS, "UN", "2026-10-17", loaded),
                        new com.bank.compliance.domain.ListVersion(com.bank.compliance.domain.ScreeningList.PEP, "UAE-LOCAL", "2026-10", loaded)));

        ComplianceScreeningResponse response = ComplianceScreeningResponse.from(result);

        assertThat(response.attestation()).isEqualTo("COMPUTED");
        assertThat(response.listVersions()).containsExactly(
                new ComplianceScreeningResponse.ListVersionResponse("SANCTIONS", "UN", "2026-10-17", loaded),
                new ComplianceScreeningResponse.ListVersionResponse("PEP", "UAE-LOCAL", "2026-10", loaded));
    }
}
