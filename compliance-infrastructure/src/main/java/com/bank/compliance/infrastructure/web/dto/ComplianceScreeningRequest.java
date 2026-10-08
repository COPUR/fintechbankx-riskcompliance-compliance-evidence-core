package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Screening request. The flags and amount are attested by the caller.
 * {@code sanctionsHit} and {@code pep} are required: an omitted or null flag
 * is a 400, never stored as a caller-attested {@code false}.
 * {@code currency} is optional for callers written before it existed and
 * defaults to USD, the monolith's fallback when an amount has no currency
 * (the home-currency decision is still open); the domain validates it.
 */
public record ComplianceScreeningRequest(
        String transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        @NotNull(message = SANCTIONS_HIT_REQUIRED) Boolean sanctionsHit,
        boolean kycVerified,
        @NotNull(message = PEP_REQUIRED) Boolean pep
) {
    public static final String DEFAULT_CURRENCY = "USD";
    static final String SANCTIONS_HIT_REQUIRED = "sanctionsHit is required";
    static final String PEP_REQUIRED = "pep is required";

    public ComplianceScreeningCommand toCommand() {
        if (sanctionsHit == null) {
            throw new IllegalArgumentException(SANCTIONS_HIT_REQUIRED);
        }
        if (pep == null) {
            throw new IllegalArgumentException(PEP_REQUIRED);
        }
        return new ComplianceScreeningCommand(
                transactionId,
                customerId,
                amount,
                currency == null ? DEFAULT_CURRENCY : currency,
                sanctionsHit,
                kycVerified,
                pep
        );
    }
}
