package com.bank.compliance.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * The screened amount with its currency. Fits the evidence column
 * NUMERIC(19,4): positive, at most 15 integer digits and 4 decimal places;
 * the currency is an upper-case ISO 4217 alphabetic code. Shared by the
 * caller-attested and the computed-facts screening commands.
 */
public record ScreenedAmount(BigDecimal amount, String currency) {

    public static final int MAX_AMOUNT_SCALE = 4;
    public static final int MAX_AMOUNT_INTEGER_DIGITS = 15;
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public ScreenedAmount {
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
    }

    /** Same amount by value (100 equals 100.00) in the same currency. */
    public boolean sameValueAs(ScreenedAmount other) {
        return other != null && amount.compareTo(other.amount) == 0 && currency.equals(other.currency);
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
