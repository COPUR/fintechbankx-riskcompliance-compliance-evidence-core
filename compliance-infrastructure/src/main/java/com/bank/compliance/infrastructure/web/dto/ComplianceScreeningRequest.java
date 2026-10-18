package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Screening request. The flags and amount are attested by the caller.
 * {@code sanctionsHit}, {@code kycVerified} and {@code pep} are required: an omitted or null flag
 * is a 400, never stored as a caller-attested {@code false}.
 * {@code currency} is required too: a default would judge the amount against
 * another currency's PEP threshold and record a currency the caller never
 * stated. The domain validates the code.
 */
public record ComplianceScreeningRequest(
        String transactionId,
        String customerId,
        BigDecimal amount,
        @NotNull(message = CURRENCY_REQUIRED) String currency,
        @NotNull(message = SANCTIONS_HIT_REQUIRED) Boolean sanctionsHit,
        @NotNull(message = KYC_VERIFIED_REQUIRED) Boolean kycVerified,
        @NotNull(message = PEP_REQUIRED) Boolean pep
) {
    static final String CURRENCY_REQUIRED = "currency is required";
    static final String SANCTIONS_HIT_REQUIRED = "sanctionsHit is required";
    static final String KYC_VERIFIED_REQUIRED = "kycVerified is required";
    static final String PEP_REQUIRED = "pep is required";

    /** @param attestation who states these facts, taken from the caller's token by the web adapter */
    public ComplianceScreeningCommand toCommand(Attestation attestation) {
        if (currency == null) {
            throw new IllegalArgumentException(CURRENCY_REQUIRED);
        }
        if (sanctionsHit == null) {
            throw new IllegalArgumentException(SANCTIONS_HIT_REQUIRED);
        }
        if (kycVerified == null) {
            throw new IllegalArgumentException(KYC_VERIFIED_REQUIRED);
        }
        if (pep == null) {
            throw new IllegalArgumentException(PEP_REQUIRED);
        }
        return new ComplianceScreeningCommand(
                transactionId,
                customerId,
                amount,
                currency,
                sanctionsHit,
                kycVerified,
                pep,
                attestation
        );
    }
}
