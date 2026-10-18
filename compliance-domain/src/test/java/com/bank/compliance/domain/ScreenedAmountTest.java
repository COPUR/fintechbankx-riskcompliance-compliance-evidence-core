package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The screened amount with its currency: one validation shared by both screening commands. */
class ScreenedAmountTest {

    @Test
    void comparesByValueNotScale() {
        ScreenedAmount recorded = new ScreenedAmount(new BigDecimal("100.00"), "AED");

        assertThat(recorded.sameValueAs(new ScreenedAmount(new BigDecimal("100"), "AED"))).isTrue();
        assertThat(recorded.sameValueAs(new ScreenedAmount(new BigDecimal("100.0000"), "AED"))).isTrue();
        assertThat(recorded.sameValueAs(new ScreenedAmount(new BigDecimal("100.01"), "AED"))).isFalse();
        assertThat(recorded.sameValueAs(new ScreenedAmount(new BigDecimal("100"), "USD"))).isFalse();
        assertThat(recorded.sameValueAs(null)).isFalse();
    }

    @Test
    void rejectsWhatTheEvidenceColumnCannotHold() {
        assertThatThrownBy(() -> new ScreenedAmount(null, "AED")).hasMessage("amount must be positive");
        assertThatThrownBy(() -> new ScreenedAmount(new BigDecimal("-1"), "AED")).hasMessage("amount must be positive");
        assertThatThrownBy(() -> new ScreenedAmount(new BigDecimal("0.00001"), "AED")).hasMessage("amount must have at most 4 decimal places");
        assertThatThrownBy(() -> new ScreenedAmount(new BigDecimal("1000000000000000"), "AED")).hasMessage("amount must have at most 15 integer digits");
        assertThatThrownBy(() -> new ScreenedAmount(BigDecimal.ONE, "ZZZ")).hasMessage("currency must be an upper-case ISO 4217 code");
    }
}
