package com.bank.compliance.infrastructure.web.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceScreeningRequestTest {

    @Test
    void aMissingCurrencyIsRefusedNotDefaulted() {
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", new BigDecimal("10.00"), null, false, true, false)
                .toCommand(com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("currency is required");
    }

    @Test
    void missingSanctionsOrPepFlagsAreRefusedNotDefaulted() {
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", null, true, false).toCommand(com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sanctionsHit is required");
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", false, true, null).toCommand(com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("pep is required");
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", false, null, false)
                .toCommand(com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("kycVerified is required");
    }

    @Test
    void anExplicitCurrencyIsKeptAndValidatedByTheDomain() {
        assertThat(new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", false, true, false).toCommand(com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS).currency())
                .isEqualTo("USD");
        assertThatThrownBy(() -> new ComplianceScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "usd", false, true, false).toCommand(com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }
}
