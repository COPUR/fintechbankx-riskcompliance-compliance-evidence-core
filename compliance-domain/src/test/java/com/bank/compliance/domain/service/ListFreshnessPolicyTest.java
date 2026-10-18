package com.bank.compliance.domain.service;

import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A screening never passes by default: a list that is empty, that lacks one
 * of its required sources (COMPLIANCE_SANCTIONS_REQUIRED_SOURCES,
 * COMPLIANCE_PEP_REQUIRED_SOURCES), or whose load is older than its maximum
 * age (COMPLIANCE_LIST_MAX_AGE_SANCTIONS, default P1D;
 * COMPLIANCE_LIST_MAX_AGE_PEP, default P35D), stops the screening with
 * SCREENING_SOURCE_UNAVAILABLE instead of answering from partial or stale data.
 */
class ListFreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private final ListFreshnessPolicy policy = new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(35),
            List.of("UN", "OFAC"), List.of("UAE-LOCAL"));

    private static ListVersion loaded(ScreeningList list, String source, Instant loadedAt) {
        return new ListVersion(list, source, "2026-10-17", loadedAt);
    }

    @Test
    void theDefaultsAreOneDayForSanctionsAndThirtyFiveDaysForPep() {
        ListFreshnessPolicy defaults = ListFreshnessPolicy.defaults();

        assertThat(defaults.maxAgeOf(ScreeningList.SANCTIONS)).isEqualTo(Duration.parse("P1D"));
        assertThat(defaults.maxAgeOf(ScreeningList.PEP)).isEqualTo(Duration.parse("P35D"));
        assertThat(defaults.requiredSourcesOf(ScreeningList.SANCTIONS)).containsExactly("UN", "OFAC-SDN", "UAE-LOCAL");
        assertThat(defaults.requiredSourcesOf(ScreeningList.PEP)).containsExactly("UAE-PEP");
    }

    /** A partial set of lists must not screen as complete: a name on the missing list would pass. */
    @Test
    void aMissingRequiredSourceIsUnavailableForAHitAndForANoHit() {
        ScreeningListMatch onlyUn = new ScreeningListMatch(false, List.of(loaded(ScreeningList.SANCTIONS, "UN", NOW.minus(Duration.ofHours(1)))));
        ScreeningListMatch onlyUnHit = new ScreeningListMatch(true, List.of(loaded(ScreeningList.SANCTIONS, "UN", NOW.minus(Duration.ofHours(1)))));

        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, onlyUn, NOW))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The sanctions list is missing the required source OFAC");
        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, onlyUnHit, NOW))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The sanctions list is missing the required source OFAC");
        ScreeningListMatch otherPep = new ScreeningListMatch(false, List.of(loaded(ScreeningList.PEP, "OTHER-PEP", NOW)));
        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.PEP, otherPep, NOW))
                .hasMessage("The PEP register is missing the required source UAE-LOCAL");
    }

    @Test
    void aStaleRequiredSourceIsUnavailableEvenWhenAnotherSourceIsFresh() {
        ScreeningListMatch ofacStale = new ScreeningListMatch(true, List.of(
                loaded(ScreeningList.SANCTIONS, "UN", NOW.minus(Duration.ofHours(1))),
                loaded(ScreeningList.SANCTIONS, "OFAC", NOW.minus(Duration.ofDays(2)))));

        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, ofacStale, NOW))
                .hasMessage("The sanctions list is stale: source OFAC was loaded more than PT24H ago");
    }

    @Test
    void aSourceBeyondTheRequiredOnesIsAllowedButMustBeFreshToo() {
        ScreeningListMatch withExtra = new ScreeningListMatch(false, List.of(
                loaded(ScreeningList.SANCTIONS, "UN", NOW), loaded(ScreeningList.SANCTIONS, "OFAC", NOW),
                loaded(ScreeningList.SANCTIONS, "EXTRA-LOCAL", NOW.minus(Duration.ofHours(2)))));
        ScreeningListMatch extraStale = new ScreeningListMatch(false, List.of(
                loaded(ScreeningList.SANCTIONS, "UN", NOW), loaded(ScreeningList.SANCTIONS, "OFAC", NOW),
                loaded(ScreeningList.SANCTIONS, "EXTRA-LOCAL", NOW.minus(Duration.ofDays(2)))));

        assertThatCode(() -> policy.requireUsable(ScreeningList.SANCTIONS, withExtra, NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, extraStale, NOW))
                .hasMessage("The sanctions list is stale: source EXTRA-LOCAL was loaded more than PT24H ago");
    }

    @Test
    void requiredSourcesMustBeConfiguredAndNamed() {
        assertThatThrownBy(() -> new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(35), List.of(), List.of("UAE-PEP")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sanctions").hasMessageContaining("required source");
        assertThatThrownBy(() -> new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(35), List.of("UN"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PEP");
        assertThatThrownBy(() -> new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(35), List.of("UN", " "), List.of("UAE-PEP")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sanctions");
        assertThat(new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(35), List.of(" UN ", "OFAC"), List.of("UAE-PEP"))
                .requiredSourcesOf(ScreeningList.SANCTIONS)).containsExactly("UN", "OFAC");
    }

    @Test
    void aListLoadedWithinItsMaximumAgeIsUsable() {
        ScreeningListMatch sanctions = new ScreeningListMatch(false, List.of(
                loaded(ScreeningList.SANCTIONS, "UN", NOW.minus(Duration.ofHours(23).plusMinutes(59))),
                loaded(ScreeningList.SANCTIONS, "OFAC", NOW.minus(Duration.ofHours(2)))));
        ScreeningListMatch pep = new ScreeningListMatch(true, List.of(
                loaded(ScreeningList.PEP, "UAE-LOCAL", NOW.minus(Duration.ofDays(34)))));

        assertThatCode(() -> policy.requireUsable(ScreeningList.SANCTIONS, sanctions, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireUsable(ScreeningList.PEP, pep, NOW)).doesNotThrowAnyException();
    }

    @Test
    void aListWithNoLoadedSourceIsUnavailableEvenWithoutAHit() {
        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, ScreeningListMatch.none(), NOW))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The sanctions list is empty");
        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.PEP, ScreeningListMatch.none(), NOW))
                .hasMessage("The PEP register is empty");
    }

    @Test
    void oneStaleSourceMakesTheWholeListUnavailable() {
        ScreeningListMatch oneSourceStale = new ScreeningListMatch(false, List.of(
                loaded(ScreeningList.SANCTIONS, "UN", NOW.minus(Duration.ofHours(1))),
                loaded(ScreeningList.SANCTIONS, "OFAC", NOW.minus(Duration.ofHours(24).plusSeconds(1)))));

        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, oneSourceStale, NOW))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The sanctions list is stale: source OFAC was loaded more than PT24H ago");
    }

    @Test
    void exactlyAtTheMaximumAgeIsStillFresh() {
        ScreeningListMatch atTheLimit = new ScreeningListMatch(false, List.of(
                loaded(ScreeningList.PEP, "UAE-LOCAL", NOW.minus(Duration.ofDays(35)))));

        assertThatCode(() -> policy.requireUsable(ScreeningList.PEP, atTheLimit, NOW)).doesNotThrowAnyException();
    }

    @Test
    void aHitOnAStaleListIsNotAnsweredEither() {
        // Fail closed in both directions: a stale list cannot FAIL a transaction any more than it can PASS one.
        ScreeningListMatch staleHit = new ScreeningListMatch(true, List.of(
                loaded(ScreeningList.SANCTIONS, "UN", NOW.minus(Duration.ofDays(3)))));

        assertThatThrownBy(() -> policy.requireUsable(ScreeningList.SANCTIONS, staleHit, NOW))
                .isInstanceOf(ScreeningSourceUnavailableException.class);
    }

    @Test
    void maximumAgesMustBePositive() {
        assertThatThrownBy(() -> new ListFreshnessPolicy(Duration.ZERO, Duration.ofDays(35), List.of("UN"), List.of("UAE-PEP")))
                .hasMessageContaining("sanctions");
        assertThatThrownBy(() -> new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(-1), List.of("UN"), List.of("UAE-PEP")))
                .hasMessageContaining("PEP");
        assertThatThrownBy(() -> new ListFreshnessPolicy(null, Duration.ofDays(35), List.of("UN"), List.of("UAE-PEP")))
                .hasMessageContaining("sanctions");
    }

    @Test
    void listVersionsAreComplete() {
        assertThatThrownBy(() -> new ListVersion(null, "UN", "v1", NOW)).hasMessageContaining("list");
        assertThatThrownBy(() -> new ListVersion(ScreeningList.SANCTIONS, " ", "v1", NOW)).hasMessageContaining("source");
        assertThatThrownBy(() -> new ListVersion(ScreeningList.SANCTIONS, "UN", "", NOW)).hasMessageContaining("version");
        assertThatThrownBy(() -> new ListVersion(ScreeningList.SANCTIONS, "UN", "v1", null)).hasMessageContaining("loadedAt");
        assertThatThrownBy(() -> new ScreeningListMatch(true, null)).hasMessageContaining("sources");
    }
}
