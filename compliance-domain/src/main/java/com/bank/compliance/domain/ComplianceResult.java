package com.bank.compliance.domain;

import com.bank.compliance.domain.port.in.ComputedFactsScreeningCommand;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The recorded outcome of screening one transaction: the decision, its
 * reasons, the facts it was made on, where they came from, the rule set that
 * made it and, for facts compliance computed, the list loads they were
 * matched against. Evidence: written once, never changed.
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
    private final List<ListVersion> listVersions;

    private ComplianceResult(
            ComplianceResultId id,
            String transactionId,
            String customerId,
            ScreeningFacts facts,
            ComplianceDecision decision,
            List<String> reasons,
            String ruleSetVersion,
            Instant checkedAt,
            List<ListVersion> listVersions
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
        this.listVersions = List.copyOf(Objects.requireNonNull(listVersions, "listVersions are required"));
    }

    /** A screening on caller-attested facts (v1): no list was consulted. */
    public static ComplianceResult create(
            String transactionId,
            String customerId,
            ScreeningFacts facts,
            ComplianceDecision decision,
            List<String> reasons,
            String ruleSetVersion
    ) {
        return create(transactionId, customerId, facts, decision, reasons, ruleSetVersion, List.of());
    }

    /** @param listVersions the list loads the facts were matched against (computed facts) */
    public static ComplianceResult create(
            String transactionId,
            String customerId,
            ScreeningFacts facts,
            ComplianceDecision decision,
            List<String> reasons,
            String ruleSetVersion,
            List<ListVersion> listVersions
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
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                listVersions
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
                snapshot.checkedAt(),
                snapshot.listVersions()
        );
    }

    /**
     * True when a repeated screening request for this transaction is a replay
     * of the recorded one: same customer, the same facts and the same caller
     * attesting them. Only then may the stored result be returned for it;
     * anything else would hand out evidence for facts that were never
     * screened, or that another caller stated.
     */
    public boolean isReplayOf(String otherCustomerId, ScreeningFacts otherFacts) {
        return customerId.equals(otherCustomerId)
                && facts.sameFactsAs(otherFacts)
                && facts.attestation().equals(otherFacts.attestation());
    }

    /**
     * True when a repeated computed-facts request (v2) is a replay of this
     * recorded computed screening: same customer, same amount by value and
     * currency, and the same requester. The flags are not the caller's to
     * repeat, so they are not compared; a result whose facts were stated by a
     * caller (v1) is never a replay of a computed request.
     */
    public boolean isReplayOf(ComputedFactsScreeningCommand command) {
        return customerId.equals(command.customerId())
                && facts.screenedAmount().sameValueAs(command.screenedAmount())
                && facts.attestation().equals(command.requestedBy());
    }

    /**
     * The fact that this screening was made, for publication after the result
     * is first stored. The event occurred when the screening was checked. It
     * carries the outcome only: no facts, amount or list data.
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
        return facts.attestation().source();
    }

    /** The caller who stated the facts (token azp for a service, sub for staff), or who requested a computed screening. */
    public String getAttestedBy() {
        return facts.attestation().attestedBy();
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

    /** The list loads a computed screening was matched against; empty for caller-attested facts. */
    public List<ListVersion> getListVersions() {
        return listVersions;
    }

    public boolean isPassed() {
        return decision == ComplianceDecision.PASS;
    }
}
