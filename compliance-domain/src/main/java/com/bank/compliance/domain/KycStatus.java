package com.bank.compliance.domain;

/**
 * The customer context's KYC status (customer-context.yaml KycStatusResponse.kycStatus).
 * Only VERIFIED counts as verified; an unknown value from the customer service
 * is contract drift and is not mapped to any of these.
 */
public enum KycStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
