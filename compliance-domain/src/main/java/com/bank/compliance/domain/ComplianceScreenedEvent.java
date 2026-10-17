package com.bank.compliance.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A transaction was screened and the result recorded as evidence. Raised once,
 * when the result is first stored; a retried screening returns the stored
 * result and raises nothing.
 *
 * Carries the decision and its reason codes only. The screening inputs
 * (sanctions, PEP and KYC flags, amount) stay in the service.
 */
public record ComplianceScreenedEvent(
        UUID eventId,
        Instant occurredAt,
        ComplianceResultId screeningId,
        String transactionId,
        String customerId,
        ComplianceDecision decision,
        List<String> reasons,
        Instant checkedAt
) {
    public ComplianceScreenedEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(screeningId, "screeningId is required");
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(decision, "decision is required");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        Objects.requireNonNull(checkedAt, "checkedAt is required");
    }
}
