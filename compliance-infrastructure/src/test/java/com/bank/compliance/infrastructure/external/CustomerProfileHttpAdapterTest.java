package com.bank.compliance.infrastructure.external;

import com.bank.compliance.domain.CustomerProfile;
import com.bank.compliance.domain.KycStatus;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import com.bank.compliance.infrastructure.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Anti-corruption adapter for the customer context's published API
 * (customer-context.yaml): GET /api/v1/customers/{id}/screening-profile
 * answers exactly what a screening needs (customerId, firstName, lastName,
 * kycStatus) to a listed service caller (SERVICE_CALLERS_SCREENING, aud
 * svc-cus-profile-kyc); compliance never reads the full profile. The call
 * carries the service's own client-credentials token and the request's
 * interaction id. Anything but a definite answer is a source outage: the
 * screening never guesses a fact.
 */
class CustomerProfileHttpAdapterTest {

    private static final String BASE = "http://customer.test";
    private static final String PROFILE = """
            {"customerId":"C-1","firstName":"Test","lastName":"Clean Pérson","kycStatus":"VERIFIED"}
            """;

    private MockRestServiceServer server;
    private CustomerProfileHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CustomerProfileHttpAdapter(builder.build(), () -> "stub-service-token");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void readsTheNameAndTheKycStatusWithTheServiceTokenAndTheInteractionId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "it-interaction-9");
        server.expect(once(), requestTo(BASE + "/api/v1/customers/C-1/screening-profile")).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stub-service-token"))
                .andExpect(header(CorrelationIdFilter.HEADER, "it-interaction-9"))
                .andRespond(withSuccess(PROFILE, MediaType.APPLICATION_JSON));

        Optional<CustomerProfile> profile = adapter.findCustomer("C-1");

        assertThat(profile).contains(new CustomerProfile("C-1", "Test", "Clean Pérson", KycStatus.VERIFIED));
        assertThat(profile.orElseThrow().fullName().value()).isEqualTo("TEST CLEAN PERSON");
        server.verify();
    }

    @Test
    void withoutARequestContextAnInteractionIdIsStillSent() {
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile"))
                .andExpect(header(CorrelationIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
                .andRespond(withSuccess(PROFILE.replace("VERIFIED", "PENDING"), MediaType.APPLICATION_JSON));

        assertThat(adapter.findCustomer("C-1").orElseThrow().kycStatus()).isEqualTo(KycStatus.PENDING);
        server.verify();
    }

    @Test
    void aCustomerThatDoesNotExistIsEmptyNotAnOutage() {
        server.expect(requestTo(BASE + "/api/v1/customers/C-404/screening-profile")).andRespond(withResourceNotFound()
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CUSTOMER_NOT_FOUND\",\"message\":\"Customer not found\"}"));

        assertThat(adapter.findCustomer("C-404")).isEmpty();
        server.verify();
    }

    @Test
    void aServerErrorIsASourceOutageWithoutTheCustomerId() {
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The customer profile service answered HTTP 500")
                .message().doesNotContain("C-1");
    }

    @Test
    void aRefusedTokenIsASourceOutageNotANotFound() {
        // 401/403: this service's client is not entitled yet (aud svc-cus-profile-kyc, SERVICE_CALLERS_SCREENING); never read as "no customer".
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The customer profile service answered HTTP 403");
    }

    @Test
    void anUnreachableServiceIsASourceOutage() {
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile"))
                .andRespond(request -> { throw new ResourceAccessException("connect timed out", new IOException("connect timed out")); });

        assertThatThrownBy(() -> adapter.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The customer profile service is unreachable");
    }

    @Test
    void anUnknownOrMissingKycStatusIsContractDriftNotAFact() {
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile"))
                .andRespond(withSuccess(PROFILE.replace("VERIFIED", "ESCALATED"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The customer profile service answered with an unknown KYC status");

        // A profile without kycStatus (an older customer release).
        server.reset();
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile"))
                .andRespond(withSuccess(PROFILE.replace(",\"kycStatus\":\"VERIFIED\"", ""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> adapter.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The customer profile service answered with an unknown KYC status");
    }

    @Test
    void aProfileWithoutANameCannotBeScreened() {
        server.expect(requestTo(BASE + "/api/v1/customers/C-1/screening-profile"))
                .andRespond(withSuccess(PROFILE.replace("\"Clean Pérson\"", "\" \""), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The customer profile has no usable name to screen");
    }

    @Test
    void aTokenThatCannotBeObtainedStopsTheLookupBeforeAnyCall() {
        CustomerProfileHttpAdapter withoutToken = new CustomerProfileHttpAdapter(RestClient.builder().baseUrl(BASE).build(),
                () -> { throw new ScreeningSourceUnavailableException("No client credential is configured for the customer profile service"); });

        assertThatThrownBy(() -> withoutToken.findCustomer("C-1"))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessageContaining("No client credential");
        server.verify();
    }
}
