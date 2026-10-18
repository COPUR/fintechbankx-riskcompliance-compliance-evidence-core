package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * When a screening list may be screened against: it must hold at least one
 * source load, and every source must have been loaded within the list's
 * maximum age (COMPLIANCE_LIST_MAX_AGE_SANCTIONS, default one day;
 * COMPLIANCE_LIST_MAX_AGE_PEP, default 35 days). Otherwise the screening is
 * refused as SCREENING_SOURCE_UNAVAILABLE, hit or no hit: a stale list can no
 * more FAIL a transaction than PASS one.
 */
public final class ListFreshnessPolicy {

    private static final Duration DEFAULT_SANCTIONS_MAX_AGE = Duration.ofDays(1);
    private static final Duration DEFAULT_PEP_MAX_AGE = Duration.ofDays(35);

    private final Duration sanctionsMaxAge;
    private final Duration pepMaxAge;

    public ListFreshnessPolicy(Duration sanctionsMaxAge, Duration pepMaxAge) {
        this.sanctionsMaxAge = requirePositive("sanctions", sanctionsMaxAge);
        this.pepMaxAge = requirePositive("PEP", pepMaxAge);
    }

    public static ListFreshnessPolicy defaults() {
        return new ListFreshnessPolicy(DEFAULT_SANCTIONS_MAX_AGE, DEFAULT_PEP_MAX_AGE);
    }

    public Duration maxAgeOf(ScreeningList list) {
        return switch (Objects.requireNonNull(list, "list is required")) {
            case SANCTIONS -> sanctionsMaxAge;
            case PEP -> pepMaxAge;
        };
    }

    /** @throws ScreeningSourceUnavailableException when the list is empty or any source is older than its maximum age */
    public void requireUsable(ScreeningList list, ScreeningListMatch match, Instant now) {
        Objects.requireNonNull(match, "match is required");
        Objects.requireNonNull(now, "now is required");
        if (match.sources().isEmpty()) {
            throw new ScreeningSourceUnavailableException("The " + list.displayName() + " is empty");
        }
        Duration maxAge = maxAgeOf(list);
        Instant oldestAllowed = now.minus(maxAge);
        for (ListVersion source : match.sources()) {
            if (source.loadedAt().isBefore(oldestAllowed)) {
                throw new ScreeningSourceUnavailableException("The " + list.displayName() + " is stale: source "
                        + source.source() + " was loaded more than " + maxAge + " ago");
            }
        }
    }

    private static Duration requirePositive(String list, Duration maxAge) {
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("the " + list + " list maximum age must be positive");
        }
        return maxAge;
    }
}
