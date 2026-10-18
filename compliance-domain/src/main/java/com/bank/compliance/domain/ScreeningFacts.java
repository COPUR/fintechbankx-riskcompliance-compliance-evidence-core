package com.bank.compliance.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The facts a screening decision was made on, stored with the result as
 * evidence. They are not published on events.
 *
 * Amounts fit the evidence column NUMERIC(19,4): at most 15 integer digits and
 * 4 decimal places. Currency is an ISO 4217 alphabetic code in upper case.
 */
public record ScreeningFacts(
        BigDecimal amount,
        String currency,
        boolean sanctionsHit,
        boolean kycVerified,
        boolean pep,
        Attestation attestation
) {
    public static final int MAX_AMOUNT_SCALE = ScreenedAmount.MAX_AMOUNT_SCALE;
    public static final int MAX_AMOUNT_INTEGER_DIGITS = ScreenedAmount.MAX_AMOUNT_INTEGER_DIGITS;

    public ScreeningFacts {
        // Amount and currency rules live in ScreenedAmount, shared with the computed-facts command.
        new ScreenedAmount(amount, currency);
        Objects.requireNonNull(attestation, "attestation is required");
    }

    /** The amount and currency as one value. */
    public ScreenedAmount screenedAmount() {
        return new ScreenedAmount(amount, currency);
    }

    /**
     * True when another request states the same facts: same amount by value
     * (100 equals 100.00), currency and flags. Who attested them is not part
     * of this comparison; {@link ComplianceResult#isReplayOf} checks it.
     */
    public boolean sameFactsAs(ScreeningFacts other) {
        return other != null
                && screenedAmount().sameValueAs(other.screenedAmount())
                && sanctionsHit == other.sanctionsHit
                && kycVerified == other.kycVerified
                && pep == other.pep;
    }
}
