package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceScreenedEventTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-10-08T09:15:30.123456Z");

    private static ComplianceResult stored(ComplianceDecision decision, List<String> reasons) {
        return ComplianceResult.rehydrate(new ComplianceResultSnapshot(
                ComplianceResultId.of("CMP-0b9d1c5e-3f4a-4d8e-9a71-2c6f0e5b7d10"),
                "PAY-77", "C-42", decision, reasons, CHECKED_AT));
    }

    @Test
    void screenedEventCarriesTheFactsOfTheRecordedDecision() {
        ComplianceResult result = stored(ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"));

        ComplianceScreenedEvent event = result.screenedEvent();

        assertThat(event.eventId()).isNotNull();
        assertThat(event.screeningId()).isEqualTo(result.getId());
        assertThat(event.transactionId()).isEqualTo("PAY-77");
        assertThat(event.customerId()).isEqualTo("C-42");
        assertThat(event.decision()).isEqualTo(ComplianceDecision.REVIEW);
        assertThat(event.reasons()).containsExactly("PEP_HIGH_VALUE_REVIEW");
        assertThat(event.checkedAt()).isEqualTo(CHECKED_AT);
        // The fact happened when the screening was made, not when the event object was built.
        assertThat(event.occurredAt()).isEqualTo(CHECKED_AT);
    }

    @Test
    void everyScreenedEventHasItsOwnId() {
        ComplianceResult result = stored(ComplianceDecision.PASS, List.of("COMPLIANT"));

        assertThat(result.screenedEvent().eventId()).isNotEqualTo(result.screenedEvent().eventId());
    }

    @Test
    void reasonsCannotBeChangedAfterTheEventIsRaised() {
        List<String> reasons = new ArrayList<>(List.of("SANCTIONS_HIT"));
        ComplianceScreenedEvent event = new ComplianceScreenedEvent(UUID.randomUUID(), CHECKED_AT,
                ComplianceResultId.of("CMP-1"), "PAY-1", "C-1", ComplianceDecision.FAIL, reasons, CHECKED_AT);
        reasons.add("TAMPERED");

        assertThat(event.reasons()).containsExactly("SANCTIONS_HIT");
        assertThatThrownBy(() -> event.reasons().add("X")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void incompleteEventsAreRejected() {
        assertThatThrownBy(() -> new ComplianceScreenedEvent(null, CHECKED_AT, ComplianceResultId.of("CMP-1"),
                "PAY-1", "C-1", ComplianceDecision.PASS, List.of(), CHECKED_AT))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new ComplianceScreenedEvent(UUID.randomUUID(), CHECKED_AT, null,
                "PAY-1", "C-1", ComplianceDecision.PASS, List.of(), CHECKED_AT))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("screeningId");
        assertThatThrownBy(() -> new ComplianceScreenedEvent(UUID.randomUUID(), CHECKED_AT, ComplianceResultId.of("CMP-1"),
                "PAY-1", "C-1", null, List.of(), CHECKED_AT))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("decision");
    }
}
