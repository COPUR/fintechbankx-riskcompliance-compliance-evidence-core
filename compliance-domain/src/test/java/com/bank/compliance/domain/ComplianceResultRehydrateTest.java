package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceResultRehydrateTest {

    private static final Instant CHECKED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void rehydrateKeepsTheRecordedDecision() {
        ComplianceResult stored = ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-1"), "TX-1", "42", ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"), CHECKED));

        assertThat(stored.getId()).isEqualTo(ComplianceResultId.of("CMP-1"));
        assertThat(stored.getDecision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(stored.isPassed()).isFalse();
        assertThat(stored.getReasons()).containsExactly("SANCTIONS_HIT");
        assertThat(stored.getCheckedAt()).isEqualTo(CHECKED);
    }

    @Test
    void rehydrateStillValidatesTheRow() {
        assertThatThrownBy(() -> ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-2"), "TX-2", " ", ComplianceDecision.PASS, List.of(), CHECKED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isForComparesTheScreenedCustomer() {
        ComplianceResult result = ComplianceResult.create("TX-3", "42", ComplianceDecision.PASS, List.of("COMPLIANT"));

        assertThat(result.isFor("42")).isTrue();
        assertThat(result.isFor("43")).isFalse();
        assertThat(result.getCheckedAt().getNano() % 1000).isZero();
    }
}
