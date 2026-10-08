package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PepHighValueThresholdsTest {

    @Test
    void theDefaultPolicyConfiguresUsdOnlyAtTenThousand() {
        PepHighValueThresholds defaults = PepHighValueThresholds.defaults();

        assertThat(defaults.forCurrency("USD")).contains(new BigDecimal("10000"));
        assertThat(defaults.forCurrency("AED")).isEmpty();
        assertThat(defaults.forCurrency("EUR")).isEmpty();
    }

    @Test
    void aConfiguredCurrencyUsesItsOwnThreshold() {
        ComplianceRuleService rules = new ComplianceRuleService(PepHighValueThresholds.of(Map.of(
                "USD", new BigDecimal("10000"),
                "AED", new BigDecimal("36725"))));

        ComplianceResult below = rules.screen(command("AED", "36725.00"));
        ComplianceResult above = rules.screen(command("AED", "36725.01"));

        assertThat(below.getDecision()).isEqualTo(ComplianceDecision.PASS);
        assertThat(above.getDecision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(above.getReasons()).containsExactly("PEP_HIGH_VALUE_REVIEW");
    }

    @Test
    void thresholdsMustBePositiveAndKeyedByIsoCodes() {
        assertThatThrownBy(() -> PepHighValueThresholds.of(Map.of("usd", BigDecimal.TEN)))
                .hasMessage("threshold currency must be an upper-case ISO 4217 code: usd");
        assertThatThrownBy(() -> PepHighValueThresholds.of(Map.of("ZZZ", BigDecimal.TEN)))
                .hasMessage("threshold currency must be an upper-case ISO 4217 code: ZZZ");
        assertThatThrownBy(() -> PepHighValueThresholds.of(Map.of("USD", BigDecimal.ZERO)))
                .hasMessage("threshold for USD must be positive");
        assertThatThrownBy(() -> PepHighValueThresholds.of(null)).hasMessage("thresholds are required");
    }

    private static ComplianceScreeningCommand command(String currency, String amount) {
        return new ComplianceScreeningCommand("TX-" + amount, "C1", new BigDecimal(amount), currency, false, true, true);
    }
}
