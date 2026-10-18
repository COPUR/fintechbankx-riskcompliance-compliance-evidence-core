package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * When a screening list may be screened against: it must hold every source
 * compliance requires of it (COMPLIANCE_SANCTIONS_REQUIRED_SOURCES, for
 * example UN, OFAC-SDN and the local list; COMPLIANCE_PEP_REQUIRED_SOURCES),
 * and every source it holds must have been loaded within the list's maximum
 * age (COMPLIANCE_LIST_MAX_AGE_SANCTIONS, default one day;
 * COMPLIANCE_LIST_MAX_AGE_PEP, default 35 days). Otherwise the screening is
 * refused as SCREENING_SOURCE_UNAVAILABLE, hit or no hit: a partial or stale
 * list can no more FAIL a transaction than PASS one, and a name on the
 * missing list must not pass.
 */
public final class ListFreshnessPolicy {

    private static final Duration DEFAULT_SANCTIONS_MAX_AGE = Duration.ofDays(1);
    private static final Duration DEFAULT_PEP_MAX_AGE = Duration.ofDays(35);
    /** The sources the runbook names for cut-over; the environment overrides them. */
    private static final List<String> DEFAULT_SANCTIONS_SOURCES = List.of("UN", "OFAC-SDN", "UAE-LOCAL");
    private static final List<String> DEFAULT_PEP_SOURCES = List.of("UAE-PEP");

    private final Duration sanctionsMaxAge;
    private final Duration pepMaxAge;
    private final List<String> requiredSanctionsSources;
    private final List<String> requiredPepSources;

    /**
     * @param requiredSanctionsSources the list_source names every sanctions screening must find loaded and fresh; at least one
     * @param requiredPepSources       the same for the PEP register
     */
    public ListFreshnessPolicy(Duration sanctionsMaxAge, Duration pepMaxAge,
                               List<String> requiredSanctionsSources, List<String> requiredPepSources) {
        this.sanctionsMaxAge = requirePositive("sanctions", sanctionsMaxAge);
        this.pepMaxAge = requirePositive("PEP", pepMaxAge);
        this.requiredSanctionsSources = requireSources("sanctions", requiredSanctionsSources);
        this.requiredPepSources = requireSources("PEP", requiredPepSources);
    }

    public static ListFreshnessPolicy defaults() {
        return new ListFreshnessPolicy(DEFAULT_SANCTIONS_MAX_AGE, DEFAULT_PEP_MAX_AGE, DEFAULT_SANCTIONS_SOURCES, DEFAULT_PEP_SOURCES);
    }

    public Duration maxAgeOf(ScreeningList list) {
        return switch (Objects.requireNonNull(list, "list is required")) {
            case SANCTIONS -> sanctionsMaxAge;
            case PEP -> pepMaxAge;
        };
    }

    /** The sources a screening against this list must find loaded and fresh, in configured order. */
    public List<String> requiredSourcesOf(ScreeningList list) {
        return switch (Objects.requireNonNull(list, "list is required")) {
            case SANCTIONS -> requiredSanctionsSources;
            case PEP -> requiredPepSources;
        };
    }

    /**
     * @throws ScreeningSourceUnavailableException when the list is empty, lacks a required source,
     *                                             or any source is older than its maximum age
     */
    public void requireUsable(ScreeningList list, ScreeningListMatch match, Instant now) {
        Objects.requireNonNull(match, "match is required");
        Objects.requireNonNull(now, "now is required");
        if (match.sources().isEmpty()) {
            throw new ScreeningSourceUnavailableException("The " + list.displayName() + " is empty");
        }
        Set<String> present = match.sources().stream().map(ListVersion::source).collect(Collectors.toSet());
        for (String required : requiredSourcesOf(list)) {
            if (!present.contains(required)) {
                throw new ScreeningSourceUnavailableException("The " + list.displayName() + " is missing the required source " + required);
            }
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

    private static List<String> requireSources(String list, List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("at least one required source must be configured for the " + list + " list");
        }
        List<String> trimmed = new ArrayList<>(sources.size());
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("a required source of the " + list + " list is blank");
            }
            trimmed.add(source.trim());
        }
        return List.copyOf(trimmed);
    }
}
