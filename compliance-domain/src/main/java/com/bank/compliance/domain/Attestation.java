package com.bank.compliance.domain;

import java.util.Objects;

/**
 * Who stated the screening facts, kept with them as evidence: the source
 * (a calling service or compliance staff) and the caller's identity, the
 * token's azp (client id) for a service or its sub (user id) for staff.
 */
public record Attestation(AttestationSource source, String attestedBy) {

    /** Column size of compliance_screening.attested_by. */
    public static final int MAX_ATTESTED_BY_LENGTH = 128;

    public Attestation {
        Objects.requireNonNull(source, "attestation source is required");
        if (attestedBy == null || attestedBy.isBlank()) {
            throw new IllegalArgumentException("attestedBy is required");
        }
        if (attestedBy.length() > MAX_ATTESTED_BY_LENGTH) {
            throw new IllegalArgumentException("attestedBy must be at most " + MAX_ATTESTED_BY_LENGTH + " characters");
        }
    }

    /** A calling service, identified by its OAuth2 client id (token azp). */
    public static Attestation byService(String clientId) {
        return new Attestation(AttestationSource.CALLER_ATTESTED, clientId);
    }

    /** A compliance officer or administrator, identified by the token subject. */
    public static Attestation byStaff(String subject) {
        return new Attestation(AttestationSource.STAFF_ATTESTED, subject);
    }
}
