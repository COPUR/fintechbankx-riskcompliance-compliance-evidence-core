package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PEP high-value threshold is per currency. USD keeps today's 10,000; an
 * amount in a currency with no threshold is never decided low-value: a PEP
 * screening in it goes to REVIEW with UNSUPPORTED_CURRENCY, and the rules
 * that do not depend on the amount still apply.
 */
class ComplianceRuleServiceCurrencyTest {

    private final ComplianceRuleService rules = new ComplianceRuleService();

    @Test
    void usdPepAtTheThresholdPassesAndOneCentAboveIsReviewed() {
        ComplianceResult atThreshold = rules.screen(command("USD", "10000.00", false, true, true));
        ComplianceResult above = rules.screen(command("USD", "10000.01", false, true, true));

        assertThat(atThreshold.getDecision()).isEqualTo(ComplianceDecision.PASS);
        assertThat(atThreshold.getReasons()).containsExactly("COMPLIANT");
        assertThat(above.getDecision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(above.getReasons()).containsExactly("PEP_HIGH_VALUE_REVIEW");
    }

    @Test
    void aPepInACurrencyWithoutAThresholdIsReviewedEvenForASmallAmount() {
        ComplianceResult aed = rules.screen(command("AED", "50.00", false, true, true));
        ComplianceResult jpy = rules.screen(command("JPY", "1000000", false, true, true));

        assertThat(aed.getDecision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(aed.getReasons()).containsExactly("UNSUPPORTED_CURRENCY");
        assertThat(jpy.getDecision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(jpy.getReasons()).containsExactly("UNSUPPORTED_CURRENCY");
    }

    @Test
    void withoutThePepFlagTheNonAmountRulesDecideInAnyCurrency() {
        ComplianceResult clean = rules.screen(command("AED", "50000.00", false, true, false));
        ComplianceResult sanctioned = rules.screen(command("AED", "50.00", true, true, true));
        ComplianceResult unverified = rules.screen(command("AED", "50.00", false, false, true));

        assertThat(clean.getDecision()).isEqualTo(ComplianceDecision.PASS);
        assertThat(clean.getReasons()).containsExactly("COMPLIANT");
        assertThat(sanctioned.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(sanctioned.getReasons()).containsExactly("SANCTIONS_HIT");
        assertThat(unverified.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(unverified.getReasons()).containsExactly("KYC_NOT_VERIFIED");
    }

    @Test
    void theRuleSetVersionChangesWithTheRules() {
        assertThat(ComplianceRuleService.RULE_SET_VERSION).isEqualTo("cmp-screening-rules-v2");
        assertThat(rules.screen(command("USD", "1.00", false, true, false)).getRuleSetVersion())
                .isEqualTo("cmp-screening-rules-v2");
    }

    private static ComplianceScreeningCommand command(String currency, String amount,
                                                      boolean sanctionsHit, boolean kycVerified, boolean pep) {
        return new ComplianceScreeningCommand("TX-" + currency + "-" + amount, "C1", new BigDecimal(amount), currency,
                sanctionsHit, kycVerified, pep);
    }
}
