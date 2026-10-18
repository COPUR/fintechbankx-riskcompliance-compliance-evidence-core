package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.ScreeningFacts;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Screening rules, in order: a sanctions hit fails; unverified KYC fails; a
 * PEP's transaction above the high-value threshold for its currency goes to
 * review, and so does a PEP's transaction in a currency with no threshold
 * (UNSUPPORTED_CURRENCY: an amount is never judged against another currency's
 * threshold); otherwise it passes. The rules judge facts; whether a caller
 * stated them (v1) or compliance computed them (v2) is recorded on the
 * result's attestation, not in the rules.
 */
public class ComplianceRuleService {

    /**
     * Version of the rules below, stored with every result so evidence shows
     * which rules decided it. Change it whenever a rule or threshold changes.
     */
    public static final String RULE_SET_VERSION = "cmp-screening-rules-v2";

    private final PepHighValueThresholds pepHighValueThresholds;

    public ComplianceRuleService() {
        this(PepHighValueThresholds.defaults());
    }

    public ComplianceRuleService(PepHighValueThresholds pepHighValueThresholds) {
        this.pepHighValueThresholds = Objects.requireNonNull(pepHighValueThresholds, "pepHighValueThresholds are required");
    }

    /** Screens caller-attested facts (v1). */
    public ComplianceResult screen(ComplianceScreeningCommand command) {
        return screen(command.transactionId(), command.customerId(), command.facts(), List.of());
    }

    /**
     * Screens resolved facts and records the list loads they were matched against.
     *
     * @param listVersions the list loads consulted for computed facts; empty for caller-attested facts
     */
    public ComplianceResult screen(String transactionId, String customerId, ScreeningFacts facts, List<ListVersion> listVersions) {
        Objects.requireNonNull(facts, "facts are required");
        if (facts.sanctionsHit()) {
            return decide(transactionId, customerId, facts, listVersions, ComplianceDecision.FAIL, "SANCTIONS_HIT");
        }
        if (!facts.kycVerified()) {
            return decide(transactionId, customerId, facts, listVersions, ComplianceDecision.FAIL, "KYC_NOT_VERIFIED");
        }
        Optional<BigDecimal> pepThreshold = pepHighValueThresholds.forCurrency(facts.currency());
        if (facts.pep() && pepThreshold.isEmpty()) {
            return decide(transactionId, customerId, facts, listVersions, ComplianceDecision.REVIEW, "UNSUPPORTED_CURRENCY");
        }
        if (facts.pep() && facts.amount().compareTo(pepThreshold.orElseThrow()) > 0) {
            return decide(transactionId, customerId, facts, listVersions, ComplianceDecision.REVIEW, "PEP_HIGH_VALUE_REVIEW");
        }
        return decide(transactionId, customerId, facts, listVersions, ComplianceDecision.PASS, "COMPLIANT");
    }

    private static ComplianceResult decide(String transactionId, String customerId, ScreeningFacts facts,
                                           List<ListVersion> listVersions, ComplianceDecision decision, String reason) {
        return ComplianceResult.create(transactionId, customerId, facts, decision, List.of(reason), RULE_SET_VERSION, listVersions);
    }
}
