package com.bank.compliance.domain;

import java.util.List;
import java.util.Objects;

/**
 * The answer of a screening list for one name: whether the name is on it, and
 * every source load the list currently holds. No sources means the list is
 * empty, which the freshness policy refuses to screen against.
 */
public record ScreeningListMatch(boolean hit, List<ListVersion> sources) {

    public ScreeningListMatch {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources are required"));
    }

    /** An empty list: nothing loaded, so no hit either. */
    public static ScreeningListMatch none() {
        return new ScreeningListMatch(false, List.of());
    }
}
