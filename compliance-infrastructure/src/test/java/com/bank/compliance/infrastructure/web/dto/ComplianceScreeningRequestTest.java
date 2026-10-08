package com.bank.compliance.infrastructure.web.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceScreeningRequestTest {

    @Test
    void currencyDefaultsToUsdForCallersThatDoNotSendIt() {
        var command = new ComplianceScreeningRequest("TX-1", "C-1", new BigDecimal("10.00"), null, false, true, false).toCommand();

        assertThat(command.currency()).isEqualTo("USD");
        assertThat(command.facts().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void missingSanctionsOrPepFlagsAreRefusedNotDefaulted() {
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", null, true, false).toCommand())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sanctionsHit is required");
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", false, true, null).toCommand())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pep is required");
    }

    @Test
    void anExplicitCurrencyIsKeptAndValidatedByTheDomain() {
        assertThat(new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", false, true, false).toCommand().currency())
                .isEqualTo("USD");
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "usd", false, true, false).toCommand())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }
}
