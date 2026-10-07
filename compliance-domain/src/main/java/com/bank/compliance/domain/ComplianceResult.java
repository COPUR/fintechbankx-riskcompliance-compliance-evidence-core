package com.bank.compliance.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public final class ComplianceResult {
    private final ComplianceResultId id;
    private final String transactionId;
    private final String customerId;
    private final ComplianceDecision decision;
    private final List<String> reasons;
    private final Instant checkedAt;

    private ComplianceResult(
            ComplianceResultId id,
            String transactionId,
            String customerId,
            ComplianceDecision decision,
            List<String> reasons,
            Instant checkedAt
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.decision = Objects.requireNonNull(decision, "decision is required");
        this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        this.checkedAt = Objects.requireNonNull(checkedAt, "checkedAt is required");
    }

    public static ComplianceResult create(
            String transactionId,
            String customerId,
            ComplianceDecision decision,
            List<String> reasons
    ) {
        return new ComplianceResult(
                ComplianceResultId.generate(),
                transactionId,
                customerId,
                decision,
                reasons,
                // Microseconds: the precision the evidence is stored with, so a retry
                // returns exactly the timestamp the first response carried.
                Instant.now().truncatedTo(ChronoUnit.MICROS)
        );
    }

    /**
     * Rebuilds a stored screening result. The decision is kept as it was made,
     * even if the rules have changed since: it is evidence.
     */
    public static ComplianceResult rehydrate(ComplianceResultSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        return new ComplianceResult(
                snapshot.id(),
                snapshot.transactionId(),
                snapshot.customerId(),
                snapshot.decision(),
                snapshot.reasons(),
                snapshot.checkedAt()
        );
    }

    /**
     * True when a repeated screening request for this transaction is for the
     * same customer, so the stored result can be returned for it.
     */
    public boolean isFor(String otherCustomerId) {
        return customerId.equals(otherCustomerId);
    }

    public ComplianceResultId getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public ComplianceDecision getDecision() {
        return decision;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public boolean isPassed() {
        return decision == ComplianceDecision.PASS;
    }
}
