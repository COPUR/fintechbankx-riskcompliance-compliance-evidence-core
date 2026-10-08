package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Screening rules, in order: a sanctions hit fails; unverified KYC fails; a
 * PEP's transaction above the high-value threshold for its currency goes to
 * review, and so does a PEP's transaction in a currency with no threshold
 * (UNSUPPORTED_CURRENCY: an amount is never judged against another currency's
 * threshold); otherwise it passes.
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

    public ComplianceResult screen(ComplianceScreeningCommand command) {
        List<String> reasons = new ArrayList<>();

        if (command.sanctionsHit()) {
            reasons.add("SANCTIONS_HIT");
            return ComplianceResult.create(
                    command.transactionId(),
                    command.customerId(),
                    command.facts(),
                    ComplianceDecision.FAIL,
                    reasons,
                    RULE_SET_VERSION
            );
        }

        if (!command.kycVerified()) {
            reasons.add("KYC_NOT_VERIFIED");
            return ComplianceResult.create(
                    command.transactionId(),
                    command.customerId(),
                    command.facts(),
                    ComplianceDecision.FAIL,
                    reasons,
                    RULE_SET_VERSION
            );
        }

        Optional<BigDecimal> pepThreshold = pepHighValueThresholds.forCurrency(command.currency());
        if (command.pep() && pepThreshold.isEmpty()) {
            reasons.add("UNSUPPORTED_CURRENCY");
            return ComplianceResult.create(
                    command.transactionId(),
                    command.customerId(),
                    command.facts(),
                    ComplianceDecision.REVIEW,
                    reasons,
                    RULE_SET_VERSION
            );
        }

        if (command.pep() && command.amount().compareTo(pepThreshold.orElseThrow()) > 0) {
            reasons.add("PEP_HIGH_VALUE_REVIEW");
            return ComplianceResult.create(
                    command.transactionId(),
                    command.customerId(),
                    command.facts(),
                    ComplianceDecision.REVIEW,
                    reasons,
                    RULE_SET_VERSION
            );
        }

        reasons.add("COMPLIANT");
        return ComplianceResult.create(
                command.transactionId(),
                command.customerId(),
                command.facts(),
                ComplianceDecision.PASS,
                reasons,
                RULE_SET_VERSION
        );
    }
}
