package com.bank.compliance.domain.port.in;

import com.bank.compliance.domain.AttestationSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceScreeningCommandTest {

    @Test
    void shouldRejectInvalidCommandInput() {
        assertThatThrownBy(() -> new ComplianceScreeningCommand("", "C1", new BigDecimal("10"), "AED", false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThatThrownBy(() -> new ComplianceScreeningCommand("TX", "", new BigDecimal("10"), "AED", false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");

        assertThatThrownBy(() -> new ComplianceScreeningCommand("TX", "C1", BigDecimal.ZERO, "AED", false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");

        assertThatThrownBy(() -> new ComplianceScreeningCommand("TX", "C1", BigDecimal.TEN, "aed", false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void idsMustFitTheirColumns() {
        String max = "T".repeat(128);
        assertThatCode(() -> new ComplianceScreeningCommand(max, max, BigDecimal.TEN, "AED", false, true, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ComplianceScreeningCommand(max + "X", "C1", BigDecimal.TEN, "AED", false, true, false))
                .hasMessage("transactionId must be at most 128 characters");
        assertThatThrownBy(() -> new ComplianceScreeningCommand("TX", max + "X", BigDecimal.TEN, "AED", false, true, false))
                .hasMessage("customerId must be at most 128 characters");
    }

    @Test
    void factsAreTheCallerAttestedInputs() {
        var facts = new ComplianceScreeningCommand("TX", "C1", new BigDecimal("10.50"), "USD", true, false, true).facts();

        assertThat(facts.amount()).isEqualByComparingTo("10.50");
        assertThat(facts.currency()).isEqualTo("USD");
        assertThat(facts.sanctionsHit()).isTrue();
        assertThat(facts.kycVerified()).isFalse();
        assertThat(facts.pep()).isTrue();
        assertThat(facts.attestation()).isEqualTo(AttestationSource.CALLER_ATTESTED);
    }
}
