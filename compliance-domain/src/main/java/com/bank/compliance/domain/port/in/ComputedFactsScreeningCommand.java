package com.bank.compliance.domain.port.in;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.AttestationSource;
import com.bank.compliance.domain.ScreenedAmount;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A request to screen one transaction with the facts computed by compliance
 * (POST /api/v2/compliance/screen): ids and the amount only. The caller
 * states no sanctions, PEP or KYC fact; {@code requestedBy} records who asked,
 * taken from the verified token by the inbound adapter, as a COMPUTED
 * attestation.
 */
public record ComputedFactsScreeningCommand(
        String transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        Attestation requestedBy
) {
    public ComputedFactsScreeningCommand {
        requireId("transactionId", transactionId);
        requireId("customerId", customerId);
        // Validates amount and currency now, so a bad request never reaches a source lookup.
        new ScreenedAmount(amount, currency);
        Objects.requireNonNull(requestedBy, "requestedBy is required");
        if (requestedBy.source() != AttestationSource.COMPUTED) {
            throw new IllegalArgumentException("a computed-facts screening must be attested as COMPUTED");
        }
    }

    public ScreenedAmount screenedAmount() {
        return new ScreenedAmount(amount, currency);
    }

    private static void requireId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > ComplianceScreeningCommand.MAX_ID_LENGTH) {
            throw new IllegalArgumentException(name + " must be at most " + ComplianceScreeningCommand.MAX_ID_LENGTH + " characters");
        }
    }
}
