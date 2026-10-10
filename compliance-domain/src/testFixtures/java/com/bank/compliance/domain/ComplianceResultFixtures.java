package com.bank.compliance.domain;

import com.bank.compliance.domain.service.ComplianceRuleService;

import java.math.BigDecimal;
import java.util.List;

/** Test data: screening results made on ordinary caller-attested facts. */
public final class ComplianceResultFixtures {

    private ComplianceResultFixtures() {
    }

    /** The payment service's client id: the usual SERVICE caller. */
    public static final Attestation PAYMENTS = Attestation.byService("svc-pay-initiation-settlement");

    /** 100.00 AED, no sanctions hit, KYC verified, not a PEP, attested by the payment service. */
    public static ScreeningFacts facts() {
        return new ScreeningFacts(new BigDecimal("100.00"), "AED", false, true, false, PAYMENTS);
    }

    public static ComplianceResult result(String transactionId, String customerId,
                                          ComplianceDecision decision, List<String> reasons) {
        return ComplianceResult.create(transactionId, customerId, facts(), decision, reasons,
                ComplianceRuleService.RULE_SET_VERSION);
    }
}
