package com.bank.compliance.infrastructure.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The customer context's screening profile (customer-context.yaml,
 * GET /api/v1/customers/{id}/screening-profile): exactly the name and the KYC
 * status compliance needs. Unknown fields are ignored so an additive change
 * on the customer side does not break a screening.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CustomerProfileDto(String customerId, String firstName, String lastName, String kycStatus) {
}
