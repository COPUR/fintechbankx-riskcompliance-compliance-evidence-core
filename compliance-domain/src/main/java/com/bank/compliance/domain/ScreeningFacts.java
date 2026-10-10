package com.bank.compliance.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

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
    public static final int MAX_AMOUNT_SCALE = 4;
    public static final int MAX_AMOUNT_INTEGER_DIGITS = 15;
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public ScreeningFacts {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        BigDecimal normalised = amount.stripTrailingZeros();
        if (normalised.scale() > MAX_AMOUNT_SCALE) {
            throw new IllegalArgumentException("amount must have at most " + MAX_AMOUNT_SCALE + " decimal places");
        }
        if (normalised.precision() - normalised.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount must have at most " + MAX_AMOUNT_INTEGER_DIGITS + " integer digits");
        }
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches() || !isIso4217(currency)) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code");
        }
        Objects.requireNonNull(attestation, "attestation is required");
    }

    /**
     * True when another request states the same facts: same amount by value
     * (100 equals 100.00), currency and flags. Who attested them is not part
     * of this comparison; {@link ComplianceResult#isReplayOf} checks it.
     */
    public boolean sameFactsAs(ScreeningFacts other) {
        return other != null
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency)
                && sanctionsHit == other.sanctionsHit
                && kycVerified == other.kycVerified
                && pep == other.pep;
    }

    private static boolean isIso4217(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
