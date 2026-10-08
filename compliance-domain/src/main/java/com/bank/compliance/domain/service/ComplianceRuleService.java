package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ComplianceRuleService {

    /**
     * Version of the rules below, stored with every result so evidence shows
     * which rules decided it. Change it whenever a rule or threshold changes.
     */
    public static final String RULE_SET_VERSION = "cmp-screening-rules-v1";

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

        if (command.pep() && command.amount().compareTo(new BigDecimal("10000")) > 0) {
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
