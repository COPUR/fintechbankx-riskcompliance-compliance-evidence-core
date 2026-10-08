package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;

import java.math.BigDecimal;

/**
 * Screening request. The flags and amount are attested by the caller.
 * {@code currency} is optional for callers written before it existed and
 * defaults to USD, the monolith's fallback when an amount has no currency
 * (the home-currency decision is still open); the domain validates it.
 */
public record ComplianceScreeningRequest(
        String transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        boolean sanctionsHit,
        boolean kycVerified,
        boolean pep
) {
    public static final String DEFAULT_CURRENCY = "USD";

    public ComplianceScreeningCommand toCommand() {
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
