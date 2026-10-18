package com.bank.compliance.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One loaded source of a screening list, as the operator script left it:
 * which list, the source (for example UN, OFAC-SDN, a local list), the
 * publisher's version and when it was loaded. Stored with every computed
 * screening so the evidence names the lists the facts were matched against.
 */
public record ListVersion(ScreeningList list, String source, String version, Instant loadedAt) {

    public ListVersion {
        Objects.requireNonNull(list, "list is required");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        Objects.requireNonNull(loadedAt, "loadedAt is required");
    }
}
