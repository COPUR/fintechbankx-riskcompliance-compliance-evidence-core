package com.bank.compliance.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
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

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "sanctions_hit", nullable = false, updatable = false)
    private boolean sanctionsHit;

    @Column(name = "kyc_verified", nullable = false, updatable = false)
    private boolean kycVerified;

    @Column(name = "pep", nullable = false, updatable = false)
    private boolean pep;

    @Column(name = "attestation_source", nullable = false, length = 32, updatable = false)
    private String attestationSource;

    /** NULL only for rows written before V6 (caller not recorded); see the mapper. */
    @Column(name = "attested_by", length = 128, updatable = false)
    private String attestedBy;

    @Column(name = "rule_set_version", nullable = false, length = 64, updatable = false)
    private String ruleSetVersion;

    @Column(name = "decision", nullable = false, length = 16, updatable = false)
    private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    protected ComplianceScreeningJpaEntity() {
    }

    ComplianceScreeningJpaEntity(String screeningId, String transactionId, String customerId,
                                 BigDecimal amount, String currency, boolean sanctionsHit, boolean kycVerified,
                                 boolean pep, String attestationSource, String attestedBy, String decision, List<String> reasons,
                                 String ruleSetVersion, Instant checkedAt) {
        this.screeningId = screeningId;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.sanctionsHit = sanctionsHit;
        this.kycVerified = kycVerified;
        this.pep = pep;
        this.attestationSource = attestationSource;
        this.attestedBy = attestedBy;
        this.ruleSetVersion = ruleSetVersion;
        this.decision = decision;
        this.reasons = reasons;
        this.checkedAt = checkedAt;
    }

    public String getScreeningId() { return screeningId; }
    public String getTransactionId() { return transactionId; }
    public String getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public boolean isSanctionsHit() { return sanctionsHit; }
    public boolean isKycVerified() { return kycVerified; }
    public boolean isPep() { return pep; }
    public String getAttestationSource() { return attestationSource; }
    public String getAttestedBy() { return attestedBy; }
    public String getRuleSetVersion() { return ruleSetVersion; }
    public String getDecision() { return decision; }
    public List<String> getReasons() { return reasons; }
    public Instant getCheckedAt() { return checkedAt; }
}
