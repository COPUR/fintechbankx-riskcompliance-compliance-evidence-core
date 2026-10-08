package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreeningFactsTest {

    private static ScreeningFacts facts(String amount, String currency) {
        return new ScreeningFacts(new BigDecimal(amount), currency, false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);
    }

    @Test
    void callerAttestedFactsRecordTheirSource() {
        assertThat(facts("100.00", "AED").attestation().source()).isEqualTo(AttestationSource.CALLER_ATTESTED);
        assertThat(facts("100.00", "AED").attestation().attestedBy()).isEqualTo("svc-pay-initiation-settlement");
    }

    @Test
    void amountMustBePositive() {
        assertThatThrownBy(() -> facts("0", "AED")).hasMessage("amount must be positive");
        assertThatThrownBy(() -> facts("-0.01", "AED")).hasMessage("amount must be positive");
        assertThatThrownBy(() -> new ScreeningFacts(null, "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))
                .hasMessage("amount must be positive");
    }

    @Test
    void amountMustFitNumeric19Scale4() {
        assertThatCode(() -> facts("999999999999999.9999", "AED")).doesNotThrowAnyException();
        assertThatCode(() -> facts("12.50000000", "AED")).doesNotThrowAnyException(); // trailing zeros are not precision
        assertThatThrownBy(() -> facts("0.00001", "AED")).hasMessage("amount must have at most 4 decimal places");
        assertThatThrownBy(() -> facts("1000000000000000", "AED")).hasMessage("amount must have at most 15 integer digits");
    }

    @Test
    void currencyMustBeAnUpperCaseIso4217Code() {
        assertThatCode(() -> facts("1", "KWD")).doesNotThrowAnyException();
        for (String bad : new String[] {"aed", "AE", "AEDX", "ZZZ", "", "A1D"}) {
            assertThatThrownBy(() -> facts("1", bad)).as(bad).hasMessage("currency must be an upper-case ISO 4217 code");
        }
        assertThatThrownBy(() -> facts("1", null)).hasMessage("currency must be an upper-case ISO 4217 code");
    }

    @Test
    void attestationIsRequired() {
        assertThatThrownBy(() -> new ScreeningFacts(BigDecimal.ONE, "AED", false, true, false, null))
                .hasMessageContaining("attestation");
    }

    @Test
    void sameFactsComparesAmountByValueAndEveryFlag() {
        ScreeningFacts recorded = new ScreeningFacts(new BigDecimal("12000.00"), "AED", false, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);

        assertThat(recorded.sameFactsAs(new ScreeningFacts(new BigDecimal("12000"), "AED", false, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isTrue();
        assertThat(recorded.sameFactsAs(new ScreeningFacts(new BigDecimal("12000.01"), "AED", false, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isFalse();
        assertThat(recorded.sameFactsAs(new ScreeningFacts(new BigDecimal("12000"), "USD", false, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isFalse();
        assertThat(recorded.sameFactsAs(new ScreeningFacts(new BigDecimal("12000"), "AED", true, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isFalse();
        assertThat(recorded.sameFactsAs(new ScreeningFacts(new BigDecimal("12000"), "AED", false, false, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isFalse();
        assertThat(recorded.sameFactsAs(new ScreeningFacts(new BigDecimal("12000"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS))).isFalse();
        assertThat(recorded.sameFactsAs(null)).isFalse();
    }
}
