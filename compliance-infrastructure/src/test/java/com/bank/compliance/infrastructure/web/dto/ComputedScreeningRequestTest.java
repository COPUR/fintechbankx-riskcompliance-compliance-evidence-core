package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.ComplianceResultFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComputedScreeningRequestTest {

    private static final Attestation PAYMENTS = Attestation.computed("svc-pay-initiation-settlement");

    @Test
    void mapsIdsAmountAndCurrencyAndRecordsTheRequester() {
        var command = new ComputedScreeningRequest("TX-1", "C-1", new BigDecimal("12000.50"), "USD", null, null, null).toCommand(PAYMENTS);

        assertThat(command.transactionId()).isEqualTo("TX-1");
        assertThat(command.customerId()).isEqualTo("C-1");
        assertThat(command.amount()).isEqualByComparingTo("12000.50");
        assertThat(command.currency()).isEqualTo("USD");
        assertThat(command.requestedBy()).isEqualTo(PAYMENTS);
    }

    @Test
    void aMissingCurrencyIsRefusedNotDefaulted() {
        assertThatThrownBy(() -> new ComputedScreeningRequest("TX-1", "C-1", BigDecimal.TEN, null, null, null, null).toCommand(PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("currency is required");
    }

    /** The v1 facts are not accepted silently: a caller still sending them is on the retired contract. */
    @Test
    void callerStatedFactsAreRefused() {
        assertThatThrownBy(() -> new ComputedScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", false, null, null).toCommand(PAYMENTS))
                .hasMessage("sanctionsHit is not accepted: compliance computes it");
        assertThatThrownBy(() -> new ComputedScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", null, true, null).toCommand(PAYMENTS))
                .hasMessage("kycVerified is not accepted: compliance computes it");
        assertThatThrownBy(() -> new ComputedScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", null, null, false).toCommand(PAYMENTS))
                .hasMessage("pep is not accepted: compliance computes it");
    }

    @Test
    void theRequesterMustBeAComputedAttestation() {
        assertThatThrownBy(() -> new ComputedScreeningRequest("TX-1", "C-1", BigDecimal.TEN, "USD", null, null, null)
                .toCommand(ComplianceResultFixtures.PAYMENTS))
                .hasMessage("a computed-facts screening must be attested as COMPUTED");
    }
}
