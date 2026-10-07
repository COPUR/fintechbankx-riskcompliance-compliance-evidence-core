package com.bank.compliance.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * Row of sc_cmp_evidence.compliance_screening. Screening results are evidence:
 * written once, never updated.
 */
@Entity
@Table(name = "compliance_screening")
public class ComplianceScreeningJpaEntity {

    @Id
    @Column(name = "screening_id", length = 64)
    private String screeningId;

    @Column(name = "transaction_id", nullable = false, length = 128, updatable = false)
    private String transactionId;

    @Column(name = "customer_id", nullable = false, length = 128, updatable = false)
    private String customerId;

    @Column(name = "decision", nullable = false, length = 16, updatable = false)
    private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    protected ComplianceScreeningJpaEntity() {
    }

    ComplianceScreeningJpaEntity(String screeningId, String transactionId, String customerId, String decision,
                                 List<String> reasons, Instant checkedAt) {
        this.screeningId = screeningId;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.decision = decision;
        this.reasons = reasons;
        this.checkedAt = checkedAt;
    }

    public String getScreeningId() { return screeningId; }
    public String getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public String getDecision() { return decision; }
    public List<String> getReasons() { return reasons; }
    public Instant getCheckedAt() { return checkedAt; }
}
