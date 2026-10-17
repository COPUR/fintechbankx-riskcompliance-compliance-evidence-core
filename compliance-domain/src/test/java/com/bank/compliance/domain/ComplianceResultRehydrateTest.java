package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import com.bank.compliance.domain.service.ComplianceRuleService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceResultRehydrateTest {

    private static final Instant CHECKED = Instant.parse("2026-01-02T03:04:05Z");
    private static final ScreeningFacts SANCTIONED =
            new ScreeningFacts(new BigDecimal("250.0000"), "AED", true, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);

    @Test
    void rehydrateKeepsTheRecordedDecision() {
        ComplianceResult stored = ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-1"), "TX-1", "42", SANCTIONED, ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"),
                "cmp-screening-rules-v0", CHECKED));

        assertThat(stored.getId()).isEqualTo(ComplianceResultId.of("CMP-1"));
        assertThat(stored.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(stored.isPassed()).isFalse();
        assertThat(stored.getReasons()).containsExactly("SANCTIONS_HIT");
        assertThat(stored.getCheckedAt()).isEqualTo(CHECKED);
        assertThat(stored.getFacts()).isEqualTo(SANCTIONED);
        assertThat(stored.getAttestation()).isEqualTo(AttestationSource.CALLER_ATTESTED);
        assertThat(stored.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
        // Evidence keeps the rule set that decided it, even after the rules change.
        assertThat(stored.getRuleSetVersion()).isEqualTo("cmp-screening-rules-v0");
    }

    @Test
    void rehydrateStillValidatesTheRow() {
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", " ", SANCTIONED, ComplianceDecision.PASS, List.of(), "v1", CHECKED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", "42", null, ComplianceDecision.PASS, List.of(), "v1", CHECKED)))
                .hasMessageContaining("facts");
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", "42", SANCTIONED, ComplianceDecision.PASS, List.of(), " ", CHECKED)))
                .hasMessageContaining("ruleSetVersion");
    }

    @Test
    void aReplayMustBeForTheSameCustomerAndTheSameFacts() {
        ComplianceResult result = ComplianceResult.create("TX-3", "42", SANCTIONED, ComplianceDecision.FAIL,
                List.of("SANCTIONS_HIT"), ComplianceRuleService.RULE_SET_VERSION);

        assertThat(result.isReplayOf("42", new ScreeningFacts(new BigDecimal("250"), "AED", true, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isTrue();
        assertThat(result.isReplayOf("43", SANCTIONED)).isFalse();
        // The same transaction resent without the sanctions hit must not get the stored FAIL back as if it were its answer.
        assertThat(result.isReplayOf("42", new ScreeningFacts(new BigDecimal("250"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isFalse();
        assertThat(result.getCheckedAt().getNano() % 1000).isZero();
    }

    /**
     * The same facts stated by another caller are not a replay: the evidence
     * names who attested them, so another caller gets a 409, not this result.
     */
    @Test
    void aReplayMustComeFromTheCallerWhoAttestedTheFacts() {
        ComplianceResult result = ComplianceResult.create("TX-4", "42", SANCTIONED, ComplianceDecision.FAIL,
                List.of("SANCTIONS_HIT"), ComplianceRuleService.RULE_SET_VERSION);

        ScreeningFacts sameFactsFromLending = new ScreeningFacts(new BigDecimal("250"), "AED", true, true, false,
                Attestation.byService("svc-ln-loan-lifecycle"));
        ScreeningFacts sameFactsFromAnOfficer = new ScreeningFacts(new BigDecimal("250"), "AED", true, true, false,
                Attestation.byStaff("8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11"));

        assertThat(result.isReplayOf("42", sameFactsFromLending)).isFalse();
        assertThat(result.isReplayOf("42", sameFactsFromAnOfficer)).isFalse();
    }
}
