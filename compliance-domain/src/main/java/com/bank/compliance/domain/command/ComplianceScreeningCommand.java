package com.bank.compliance.domain.command;

import com.bank.compliance.domain.ScreeningFacts;

import java.math.BigDecimal;

/**
 * A request to screen one transaction. The flags and amount are attested by
 * the caller (see {@link com.bank.compliance.domain.AttestationSource}).
 */
public record ComplianceScreeningCommand(
        String transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        boolean sanctionsHit,
        boolean kycVerified,
        boolean pep
) {
    /** Column sizes of compliance_screening.transaction_id and customer_id. */
    public static final int MAX_ID_LENGTH = 128;

    public ComplianceScreeningCommand {
        requireId("transactionId", transactionId);
        requireId("customerId", customerId);
        // Validates amount and currency now, so a bad request never reaches the rules.
        ScreeningFacts.callerAttested(amount, currency, sanctionsHit, kycVerified, pep);
    }

    public ScreeningFacts facts() {
        return ScreeningFacts.callerAttested(amount, currency, sanctionsHit, kycVerified, pep);
    }

    private static void requireId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(name + " must be at most " + MAX_ID_LENGTH + " characters");
        }
    }
}
