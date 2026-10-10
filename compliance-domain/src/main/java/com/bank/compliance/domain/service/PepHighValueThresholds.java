package com.bank.compliance.domain.service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Amount above which a PEP's transaction goes to manual review, per currency.
 * Only currencies listed here have a threshold; the rules never compare an
 * amount with a threshold set for another currency.
 */
public final class PepHighValueThresholds {

    /** Today's policy: USD 10,000, the monolith's value. Other currencies are added by policy decision. */
    private static final PepHighValueThresholds DEFAULTS = of(Map.of("USD", new BigDecimal("10000")));

    private final Map<String, BigDecimal> byCurrency;

    private PepHighValueThresholds(Map<String, BigDecimal> byCurrency) {
        this.byCurrency = byCurrency;
    }

    public static PepHighValueThresholds defaults() {
        return DEFAULTS;
    }

    public static PepHighValueThresholds of(Map<String, BigDecimal> thresholds) {
        Objects.requireNonNull(thresholds, "thresholds are required");
        thresholds.forEach((currency, threshold) -> {
            if (currency == null || !currency.matches("[A-Z]{3}") || !isIso4217(currency)) {
                throw new IllegalArgumentException("threshold currency must be an upper-case ISO 4217 code: " + currency);
            }
            if (threshold == null || threshold.signum() <= 0) {
                throw new IllegalArgumentException("threshold for " + currency + " must be positive");
            }
        });
        return new PepHighValueThresholds(Map.copyOf(thresholds));
    }

    /** The threshold for this currency, or empty when none is configured. */
    public Optional<BigDecimal> forCurrency(String currency) {
        return Optional.ofNullable(currency == null ? null : byCurrency.get(currency));
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
