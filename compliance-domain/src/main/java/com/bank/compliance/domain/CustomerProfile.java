package com.bank.compliance.domain;

import java.util.Objects;

/**
 * What compliance reads from the customer context to screen a transaction:
 * the name to match against the lists and the KYC status. A local read
 * model of another context's published API; never persisted here.
 */
public record CustomerProfile(String customerId, String firstName, String lastName, KycStatus kycStatus) {

    public CustomerProfile {
        require("customerId", customerId);
        require("firstName", firstName);
        require("lastName", lastName);
        Objects.requireNonNull(kycStatus, "kycStatus is required");
    }

    /** The full name as matched against the sanctions and PEP lists. */
    public NormalizedName fullName() {
        return NormalizedName.of(firstName, lastName);
    }

    /** True exactly when the customer context says VERIFIED. */
    public boolean kycVerified() {
        return kycStatus == KycStatus.VERIFIED;
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
