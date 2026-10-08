package com.bank.compliance.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The recorded outcome of screening one transaction: the decision, its
 * reasons, the facts it was made on and the rule set that made it. Evidence:
 * written once, never changed.
 */
public final class ComplianceResult {
    private final ComplianceResultId id;
    private final String transactionId;
    private final String customerId;
    private final ScreeningFacts facts;
    private final ComplianceDecision decision;
    private final List<String> reasons;
    private final String ruleSetVersion;
    private final Instant checkedAt;

    private ComplianceResult(
            ComplianceResultId id,
            String transactionId,
            String customerId,
            ScreeningFacts facts,
            ComplianceDecision decision,
            List<String> reasons,
            String ruleSetVersion,
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
        this.facts = Objects.requireNonNull(facts, "facts are required");
        if (ruleSetVersion == null || ruleSetVersion.isBlank()) {
            throw new IllegalArgumentException("ruleSetVersion is required");
        }
        this.ruleSetVersion = ruleSetVersion;
        this.decision = Objects.requireNonNull(decision, "decision is required");
        this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        this.checkedAt = Objects.requireNonNull(checkedAt, "checkedAt is required");
    }

    public static ComplianceResult create(
            String transactionId,
            String customerId,
            ScreeningFacts facts,
            ComplianceDecision decision,
            List<String> reasons,
            String ruleSetVersion
    ) {
        return new ComplianceResult(
                ComplianceResultId.generate(),
                transactionId,
                customerId,
                facts,
                decision,
                reasons,
                ruleSetVersion,
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
                snapshot.facts(),
                snapshot.decision(),
                snapshot.reasons(),
                snapshot.ruleSetVersion(),
                snapshot.checkedAt()
        );
    }

    /**
     * True when a repeated screening request for this transaction is a replay
     * of the recorded one: same customer and the same facts. Only then may the
     * stored result be returned for it; anything else would hand out evidence
     * for facts that were never screened.
     */
    public boolean isReplayOf(String otherCustomerId, ScreeningFacts otherFacts) {
        return customerId.equals(otherCustomerId) && facts.sameFactsAs(otherFacts);
    }

    /**
     * The fact that this screening was made, for publication after the result
     * is first stored. The event occurred when the screening was checked.
     */
    public ComplianceScreenedEvent screenedEvent() {
        return new ComplianceScreenedEvent(UUID.randomUUID(), checkedAt, id, transactionId, customerId,
                decision, reasons, checkedAt);
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

    public ScreeningFacts getFacts() {
        return facts;
    }

    public AttestationSource getAttestation() {
        return facts.attestation();
    }

    public String getRuleSetVersion() {
        return ruleSetVersion;
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
