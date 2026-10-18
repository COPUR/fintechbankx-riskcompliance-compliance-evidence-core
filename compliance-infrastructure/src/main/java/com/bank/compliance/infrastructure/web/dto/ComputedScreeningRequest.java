package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.port.in.ComputedFactsScreeningCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Screening request on POST /api/v2/compliance/screen: ids, amount and
 * currency. Compliance computes the sanctions, PEP and KYC facts itself, so
 * the v1 flags are declared only to refuse them with a clear message: a caller
 * still sending them is on the retired contract, and a silently ignored flag
 * would hide that. {@code currency} is required for the same reason as in v1.
 */
public record ComputedScreeningRequest(
        String transactionId,
        String customerId,
        BigDecimal amount,
        @NotNull(message = CURRENCY_REQUIRED) String currency,
        Boolean sanctionsHit,
        Boolean kycVerified,
        Boolean pep
) {
    static final String CURRENCY_REQUIRED = "currency is required";
    private static final String COMPUTED_BY_COMPLIANCE = " is not accepted: compliance computes it";

    /** @param requestedBy who asked for the screening, taken from the caller's token by the web adapter (COMPUTED) */
    public ComputedFactsScreeningCommand toCommand(Attestation requestedBy) {
        if (sanctionsHit != null) {
            throw new IllegalArgumentException("sanctionsHit" + COMPUTED_BY_COMPLIANCE);
        }
        if (kycVerified != null) {
            throw new IllegalArgumentException("kycVerified" + COMPUTED_BY_COMPLIANCE);
        }
        if (pep != null) {
            throw new IllegalArgumentException("pep" + COMPUTED_BY_COMPLIANCE);
        }
        if (currency == null) {
            throw new IllegalArgumentException(CURRENCY_REQUIRED);
        }
        return new ComputedFactsScreeningCommand(transactionId, customerId, amount, currency, requestedBy);
    }
}
