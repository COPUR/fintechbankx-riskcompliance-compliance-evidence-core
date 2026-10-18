package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import com.bank.compliance.domain.port.in.ComputedFactsScreeningCommand;
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

    private static final ListVersion UN_LIST = new ListVersion(ScreeningList.SANCTIONS, "UN", "2026-10-17", CHECKED.minusSeconds(3600));
    private static final ListVersion PEP_LIST = new ListVersion(ScreeningList.PEP, "UAE-LOCAL", "2026-10", CHECKED.minusSeconds(7200));

    @Test
    void rehydrateKeepsTheRecordedDecision() {
        ComplianceResult stored = ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-1"), "TX-1", "42", SANCTIONED, ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"),
                "cmp-screening-rules-v0", CHECKED, List.of()));

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
        // A caller-attested screening consulted no list.
        assertThat(stored.getListVersions()).isEmpty();
    }

    /** The evidence of a computed screening names the list loads the facts were matched against. */
    @Test
    void rehydrateKeepsTheListVersionsOfAComputedScreening() {
        ScreeningFacts computed = new ScreeningFacts(new BigDecimal("250.00"), "AED", true, true, false,
                Attestation.computed("svc-pay-initiation-settlement"));
        ComplianceResult stored = ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-3"), "TX-3", "42", computed, ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"),
                ComplianceRuleService.RULE_SET_VERSION, CHECKED, List.of(UN_LIST, PEP_LIST)));

        assertThat(stored.getAttestation()).isEqualTo(AttestationSource.COMPUTED);
        assertThat(stored.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
        assertThat(stored.getListVersions()).containsExactly(UN_LIST, PEP_LIST);
        assertThatThrownBy(() -> stored.getListVersions().add(UN_LIST)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rehydrateStillValidatesTheRow() {
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", " ", SANCTIONED, ComplianceDecision.PASS, List.of(), "v1", CHECKED, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", "42", null, ComplianceDecision.PASS, List.of(), "v1", CHECKED, List.of())))
                .hasMessageContaining("facts");
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", "42", SANCTIONED, ComplianceDecision.PASS, List.of(), " ", CHECKED, List.of())))
                .hasMessageContaining("ruleSetVersion");
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", "42", SANCTIONED, ComplianceDecision.PASS, List.of(), "v1", CHECKED, null)))
                .hasMessageContaining("listVersions");
    }

    /**
     * A v2 replay states ids and the amount only, so it is a replay when the
     * customer, the amount by value, the currency and the requester match the
     * recorded computed screening. The flags are not the caller's to repeat.
     */
    @Test
    void aComputedReplayMatchesCustomerAmountCurrencyAndRequester() {
        Attestation payments = Attestation.computed("svc-pay-initiation-settlement");
        ComplianceResult result = ComplianceResult.create("TX-5", "42",
                new ScreeningFacts(new BigDecimal("250.00"), "AED", true, true, false, payments),
                ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"), ComplianceRuleService.RULE_SET_VERSION, List.of(UN_LIST, PEP_LIST));

        assertThat(result.isReplayOf(new ComputedFactsScreeningCommand("TX-5", "42", new BigDecimal("250"), "AED", payments))).isTrue();
        assertThat(result.isReplayOf(new ComputedFactsScreeningCommand("TX-5", "43", new BigDecimal("250"), "AED", payments))).isFalse();
        assertThat(result.isReplayOf(new ComputedFactsScreeningCommand("TX-5", "42", new BigDecimal("250.01"), "AED", payments))).isFalse();
        assertThat(result.isReplayOf(new ComputedFactsScreeningCommand("TX-5", "42", new BigDecimal("250"), "USD", payments))).isFalse();
        assertThat(result.isReplayOf(new ComputedFactsScreeningCommand("TX-5", "42", new BigDecimal("250"), "AED",
                Attestation.computed("svc-ln-loan-lifecycle")))).isFalse();
    }

    /** A v1 (caller-attested) result is never handed out as the answer to a v2 request for the same transaction. */
    @Test
    void aCallerAttestedResultIsNotAReplayOfAComputedRequest() {
        ComplianceResult callerAttested = ComplianceResult.create("TX-6", "42", SANCTIONED, ComplianceDecision.FAIL,
                List.of("SANCTIONS_HIT"), ComplianceRuleService.RULE_SET_VERSION);

        assertThat(callerAttested.isReplayOf(new ComputedFactsScreeningCommand("TX-6", "42", new BigDecimal("250"), "AED",
                Attestation.computed("svc-pay-initiation-settlement")))).isFalse();
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
