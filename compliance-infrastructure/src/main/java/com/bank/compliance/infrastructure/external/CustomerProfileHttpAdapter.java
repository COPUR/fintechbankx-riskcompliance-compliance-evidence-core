package com.bank.compliance.infrastructure.external;

import com.bank.compliance.domain.CustomerProfile;
import com.bank.compliance.domain.KycStatus;
import com.bank.compliance.domain.NormalizedName;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import com.bank.compliance.domain.port.out.CustomerProfilePort;
import com.bank.compliance.infrastructure.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a customer's name and KYC status from the customer context
 * (svc-cus-profile-kyc) through GET /api/v1/customers/{id}/screening-profile
 * of its published contract: the screening profile carries exactly
 * customerId, firstName, lastName and kycStatus, so compliance never reads
 * the full profile (DevSecOps review). Authenticated with this service's own
 * client-credentials token (the customer context lists svc-cmp-evidence on
 * SERVICE_CALLERS_SCREENING and requires aud svc-cus-profile-kyc) and
 * carrying the caller's interaction id.
 *
 * Only a definite answer becomes a fact: 200 with a usable name and a known
 * kycStatus, or the customer context's own "no such customer" (404 whose
 * ErrorResponse carries code CUSTOMER_NOT_FOUND). A bare 404 (a route, an
 * ingress or mesh answer, a wrong base path), a refusal (401/403), a server
 * error, a timeout or a response this adapter cannot read is a source outage;
 * the screening is refused rather than made on a guess, and payments keep
 * retrying instead of declining a real customer. Messages never carry the
 * customer id.
 */
public class CustomerProfileHttpAdapter implements CustomerProfilePort {

    /** The customer context's error code for an unknown customer (customer-context.yaml, ErrorResponse.code). */
    static final String CUSTOMER_NOT_FOUND_CODE = "CUSTOMER_NOT_FOUND";

    private final RestClient http;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper json = new ObjectMapper();

    public CustomerProfileHttpAdapter(RestClient http, ServiceTokenProvider tokens) {
        this.http = Objects.requireNonNull(http, "http client is required");
        this.tokens = Objects.requireNonNull(tokens, "token provider is required");
    }

    @Override
    public Optional<CustomerProfile> findCustomer(String customerId) {
        String bearer = tokens.bearerToken();
        CustomerProfileDto dto;
        try {
            dto = http.get().uri("/api/v1/customers/{id}/screening-profile", customerId)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .header(CorrelationIdFilter.HEADER, interactionId())
                    .retrieve()
                    .body(CustomerProfileDto.class);
        } catch (HttpClientErrorException.NotFound e) {
            if (isCustomerNotFound(e)) {
                return Optional.empty();
            }
            throw new ScreeningSourceUnavailableException(
                    "The customer profile service answered HTTP 404 without the customer's " + CUSTOMER_NOT_FOUND_CODE + " code");
        } catch (RestClientResponseException e) {
            throw new ScreeningSourceUnavailableException(
                    "The customer profile service answered HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new ScreeningSourceUnavailableException("The customer profile service is unreachable");
        } catch (RestClientException e) {
            // Another content type or malformed JSON: not a fact, and not a defect of ours (fail closed, 503 not 500).
            throw new ScreeningSourceUnavailableException("The customer profile service answered with an unreadable profile");
        }
        if (dto == null) {
            throw new ScreeningSourceUnavailableException("The customer profile service answered with an empty profile");
        }
        return Optional.of(toProfile(customerId, dto));
    }

    /**
     * True only for the customer context's own ErrorResponse: content type application/json
     * (its contract pins that, not application/problem+json) whose code is CUSTOMER_NOT_FOUND.
     * Anything else is not a statement about the customer.
     */
    private boolean isCustomerNotFound(HttpClientErrorException.NotFound e) {
        MediaType contentType = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return false;
        }
        try {
            JsonNode body = json.readTree(e.getResponseBodyAsByteArray());
            return body != null && CUSTOMER_NOT_FOUND_CODE.equals(body.path("code").asText(null));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static CustomerProfile toProfile(String customerId, CustomerProfileDto dto) {
        KycStatus kyc = kycStatusOf(dto.kycStatus());
        if (isBlank(dto.firstName()) || isBlank(dto.lastName())) {
            throw new ScreeningSourceUnavailableException("The customer profile has no usable name to screen");
        }
        try {
            NormalizedName.of(dto.firstName(), dto.lastName());
        } catch (IllegalArgumentException noLetters) {
            throw new ScreeningSourceUnavailableException("The customer profile has no usable name to screen");
        }
        return new CustomerProfile(customerId, dto.firstName(), dto.lastName(), kyc);
    }

    private static KycStatus kycStatusOf(String value) {
        if (value == null) {
            throw new ScreeningSourceUnavailableException("The customer profile service answered with an unknown KYC status");
        }
        try {
            return KycStatus.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ScreeningSourceUnavailableException("The customer profile service answered with an unknown KYC status");
        }
    }

    /** The current request's interaction id, so the customer context's logs join ours; a fresh one outside a request. */
    private static String interactionId() {
        String current = MDC.get(CorrelationIdFilter.MDC_KEY);
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
