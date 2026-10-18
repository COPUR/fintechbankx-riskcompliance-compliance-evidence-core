package com.bank.compliance.infrastructure.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The customer context's CustomerResponse (customer-context.yaml,
 * GET /api/v1/customers/{id}) reduced to what compliance reads: the name and
 * the KYC status it shows to listed service callers. Every other field is
 * ignored on purpose; this service does not want the credit figures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CustomerProfileDto(String customerId, String firstName, String lastName, String kycStatus) {
}
