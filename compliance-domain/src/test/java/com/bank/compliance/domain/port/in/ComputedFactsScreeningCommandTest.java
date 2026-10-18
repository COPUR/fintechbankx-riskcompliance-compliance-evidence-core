package com.bank.compliance.domain.port.in;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.AttestationSource;
import com.bank.compliance.domain.ComplianceResultFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The v2 screening request: ids and the amount only. The caller states no
 * sanctions, PEP or KYC fact; compliance computes them and records who asked.
 */
class ComputedFactsScreeningCommandTest {

    private static final Attestation REQUESTED_BY_PAYMENTS = Attestation.computed("svc-pay-initiation-settlement");

    @Test
    void carriesIdsAmountCurrencyAndTheRequester() {
        ComputedFactsScreeningCommand command =
                new ComputedFactsScreeningCommand("TX-1", "C-1", new BigDecimal("12000.50"), "USD", REQUESTED_BY_PAYMENTS);

        assertThat(command.transactionId()).isEqualTo("TX-1");
        assertThat(command.customerId()).isEqualTo("C-1");
        assertThat(command.amount()).isEqualByComparingTo("12000.50");
        assertThat(command.currency()).isEqualTo("USD");
        assertThat(command.requestedBy().source()).isEqualTo(AttestationSource.COMPUTED);
        assertThat(command.requestedBy().attestedBy()).isEqualTo("svc-pay-initiation-settlement");
    }

    @Test
    void validatesIdsAmountAndCurrencyLikeTheCallerAttestedCommand() {
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("", "C-1", BigDecimal.TEN, "USD", REQUESTED_BY_PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transactionId is required");
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "T".repeat(129), BigDecimal.TEN, "USD", REQUESTED_BY_PAYMENTS))
                .hasMessage("customerId must be at most 128 characters");
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", BigDecimal.ZERO, "USD", REQUESTED_BY_PAYMENTS))
                .hasMessage("amount must be positive");
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", new BigDecimal("1.00001"), "USD", REQUESTED_BY_PAYMENTS))
                .hasMessage("amount must have at most 4 decimal places");
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", BigDecimal.TEN, "usd", REQUESTED_BY_PAYMENTS))
                .hasMessage("currency must be an upper-case ISO 4217 code");
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", BigDecimal.TEN, null, REQUESTED_BY_PAYMENTS))
                .hasMessage("currency must be an upper-case ISO 4217 code");
    }

    @Test
    void theRequesterMustBeRecordedAsAComputedFactsAttestation() {
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", BigDecimal.TEN, "USD", null))
                .hasMessageContaining("requestedBy");
        // A caller-attested identity on a v2 request would record the facts as stated by the caller.
        assertThatThrownBy(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", BigDecimal.TEN, "USD", ComplianceResultFixtures.PAYMENTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a computed-facts screening must be attested as COMPUTED");
        assertThatCode(() -> new ComputedFactsScreeningCommand("TX-1", "C-1", BigDecimal.TEN, "USD",
                Attestation.computed("8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11"))).doesNotThrowAnyException();
    }
}
