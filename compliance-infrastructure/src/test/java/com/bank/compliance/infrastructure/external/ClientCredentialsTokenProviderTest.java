package com.bank.compliance.infrastructure.external;

import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * This repository had no client-credentials pattern to reuse, so the service
 * obtains its own token from the realm's token endpoint (grant_type
 * client_credentials, client id SVC_CLIENT_ID, credential SVC_CLIENT_SECRET
 * from a Kubernetes Secret) and caches it until shortly before it expires.
 */
class ClientCredentialsTokenProviderTest {

    private static final String TOKEN_URI = "http://identity.test/realms/fintechbankx/protocol/openid-connect/token";
    private static final Instant START = Instant.parse("2026-10-18T12:00:00Z");

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private ClientCredentialsTokenProvider provider(String credential) {
        return new ClientCredentialsTokenProvider(builder.build(), TOKEN_URI, "svc-cmp-evidence", credential, clock);
    }

    @Test
    void requestsAClientCredentialsTokenAndCachesItUntilShortlyBeforeExpiry() {
        server.expect(once(), requestTo(TOKEN_URI)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(allOf(containsString("grant_type=client_credentials"),
                        containsString("client_id=svc-cmp-evidence"), containsString("client_secret=test-client-credential"))))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":300,\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        ClientCredentialsTokenProvider provider = provider("test-client-credential");

        assertThat(provider.bearerToken()).isEqualTo("token-1");
        now.set(START.plus(Duration.ofSeconds(269)));
        assertThat(provider.bearerToken()).as("reused for 300 s minus a 30 s margin").isEqualTo("token-1");
        server.verify();

        server.expect(once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"token-2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        now.set(START.plus(Duration.ofSeconds(270)));
        assertThat(provider.bearerToken()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void withoutAConfiguredCredentialNoTokenIsRequestedAndTheSourceIsUnavailable() {
        for (String missing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> provider(missing).bearerToken())
                    .isInstanceOf(ScreeningSourceUnavailableException.class)
                    .hasMessage("No client credential is configured for the customer profile service (SVC_CLIENT_SECRET)");
        }
        server.verify();
    }

    @Test
    void aFailingTokenEndpointIsASourceOutageAndTheCredentialIsNotEchoed() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> provider("test-client-credential").bearerToken())
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The identity provider answered HTTP 500 to the token request")
                .message().is(new org.assertj.core.api.Condition<>(m -> !m.contains("test-client-credential"), "no credential"));
    }

    @Test
    void anUnreachableTokenEndpointIsASourceOutage() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(request -> { throw new ResourceAccessException("refused", new IOException("refused")); });

        assertThatThrownBy(() -> provider("test-client-credential").bearerToken())
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The identity provider is unreachable");
    }

    @Test
    void aTokenResponseWithoutAnAccessTokenIsASourceOutage() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider("test-client-credential").bearerToken())
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The identity provider answered without an access token");
    }

    @Test
    void aTokenWithoutExpiryIsUsedOnceAndRefetched() {
        server.expect(once(), requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"token-1\"}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"token-2\"}", MediaType.APPLICATION_JSON));
        ClientCredentialsTokenProvider provider = provider("test-client-credential");

        assertThat(provider.bearerToken()).isEqualTo("token-1");
        assertThat(provider.bearerToken()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void theRequestNeverCarriesTheCredentialInTheUrl() {
        server.expect(requestTo(not(containsString("client_secret"))))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":60}", MediaType.APPLICATION_JSON));

        assertThat(provider("test-client-credential").bearerToken()).isEqualTo("token-1");
    }
}
