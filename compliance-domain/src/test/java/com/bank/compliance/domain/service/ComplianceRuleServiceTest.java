package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceRuleServiceTest {

    private final ComplianceRuleService service = new ComplianceRuleService();

    @Test
    void shouldFailWhenSanctionsHit() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-1", "C1", new BigDecimal("200"), "AED", true, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);

        var result = service.screen(command);

        assertThat(result.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(result.getReasons()).contains("SANCTIONS_HIT");
        assertThat(result.getFacts()).isEqualTo(command.facts());
        assertThat(result.getRuleSetVersion()).isEqualTo(ComplianceRuleService.RULE_SET_VERSION);
    }

    @Test
    void shouldFailWhenKycMissing() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-2", "C1", new BigDecimal("200"), "AED", false, false, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);

        var result = service.screen(command);

        assertThat(result.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(result.getReasons()).contains("KYC_NOT_VERIFIED");
    }

    /** v2: the facts come resolved from their owners; the rules are the same and the list loads are kept as evidence. */
    @Test
    void screensComputedFactsWithTheSameRulesAndKeepsTheListVersions() {
        com.bank.compliance.domain.Attestation requester = com.bank.compliance.domain.Attestation.computed("svc-pay-initiation-settlement");
        com.bank.compliance.domain.ScreeningFacts facts = new com.bank.compliance.domain.ScreeningFacts(
                new BigDecimal("12000.00"), "USD", false, true, true, requester);
        List<com.bank.compliance.domain.ListVersion> lists = List.of(
                new com.bank.compliance.domain.ListVersion(com.bank.compliance.domain.ScreeningList.SANCTIONS, "UN", "2026-10-17", Instant.parse("2026-10-18T01:00:00Z")),
                new com.bank.compliance.domain.ListVersion(com.bank.compliance.domain.ScreeningList.PEP, "UAE-LOCAL", "2026-10", Instant.parse("2026-10-01T01:00:00Z")));

        var result = service.screen("TX-5", "C1", facts, lists);

        assertThat(result.getDecision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(result.getReasons()).containsExactly("PEP_HIGH_VALUE_REVIEW");
        assertThat(result.getFacts()).isEqualTo(facts);
        assertThat(result.getAttestation()).isEqualTo(com.bank.compliance.domain.AttestationSource.COMPUTED);
        assertThat(result.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
        assertThat(result.getListVersions()).containsExactlyElementsOf(lists);
        assertThat(result.getRuleSetVersion()).isEqualTo(ComplianceRuleService.RULE_SET_VERSION);

        var sanctioned = service.screen("TX-6", "C1", new com.bank.compliance.domain.ScreeningFacts(
                new BigDecimal("10.00"), "AED", true, false, false, requester), lists);
        assertThat(sanctioned.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(sanctioned.getReasons()).containsExactly("SANCTIONS_HIT");
    }

    @Test
    void shouldReviewPepHighValueAndPassNormalFlow() {
        var review = service.screen(new ComplianceScreeningCommand("TX-3", "C1", new BigDecimal("12000"), "AED", false, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS));
        var pass = service.screen(new ComplianceScreeningCommand("TX-4", "C1", new BigDecimal("900"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS));

        assertThat(review.getDecision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(pass.getDecision()).isEqualTo(ComplianceDecision.PASS);
        assertThat(pass.getReasons()).contains("COMPLIANT");
    }
}
