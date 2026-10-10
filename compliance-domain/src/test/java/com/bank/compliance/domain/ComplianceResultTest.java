package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceResultTest {

    @Test
    void shouldCreateResultAndExposeFlags() {
        ComplianceResult result = ComplianceResultFixtures.result("TX-1", "C-1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        assertThat(result.getId().getValue()).startsWith("CMP-");
        assertThat(result.getTransactionId()).isEqualTo("TX-1");
        assertThat(result.isPassed()).isTrue();
    }

    @Test
    void shouldRejectInvalidIdentifiers() {
        assertThatThrownBy(() -> ComplianceResultFixtures.result("", "C-1", ComplianceDecision.FAIL, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThatThrownBy(() -> ComplianceResultFixtures.result("TX", "", ComplianceDecision.FAIL, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
    }
}
