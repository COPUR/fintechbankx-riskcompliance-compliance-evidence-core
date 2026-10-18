package com.bank.compliance.infrastructure.external;

import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Obtains this service's own access token from the realm's token endpoint
 * with the OAuth2 client-credentials grant (client id SVC_CLIENT_ID, its
 * credential SVC_CLIENT_SECRET from a Kubernetes Secret) and reuses it until
 * shortly before it expires. This repository had no client-credentials
 * pattern to reuse before this class.
 *
 * The audience the customer context requires (aud svc-cus-profile-kyc) is
 * put on the token by the Keycloak client's audience mapper, which platform
 * configures; nothing here can add it.
 */
public class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    /** A cached token is dropped this long before it expires, so a call in flight never carries an expired one. */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final RestClient http;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String cachedToken;
    private Instant cachedUntil;

    public ClientCredentialsTokenProvider(RestClient http, String tokenUri, String clientId, String clientSecret, Clock clock) {
        this.http = Objects.requireNonNull(http, "http client is required");
        this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri is required");
        this.clientId = Objects.requireNonNull(clientId, "clientId is required");
        this.clientSecret = clientSecret;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public synchronized String bearerToken() {
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new ScreeningSourceUnavailableException(
                    "No client credential is configured for the customer profile service (SVC_CLIENT_SECRET)");
        }
        Instant now = clock.instant();
        if (cachedToken != null && cachedUntil != null && now.isBefore(cachedUntil)) {
            return cachedToken;
        }
        TokenResponseDto token = request();
        if (token == null || token.accessToken() == null || token.accessToken().isBlank()) {
            throw new ScreeningSourceUnavailableException("The identity provider answered without an access token");
        }
        // Without expires_in the token is used once: nothing says how long it is good for.
        cachedToken = token.accessToken();
        cachedUntil = token.expiresIn() == null ? null : now.plusSeconds(token.expiresIn()).minus(EXPIRY_MARGIN);
        return cachedToken;
    }

    private TokenResponseDto request() {
        // The credential travels in the form body only (RFC 6749 section 2.3.1), never in the URL or a log.
        String form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret);
        try {
            return http.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenResponseDto.class);
        } catch (RestClientResponseException e) {
            throw new ScreeningSourceUnavailableException(
                    "The identity provider answered HTTP " + e.getStatusCode().value() + " to the token request");
        } catch (ResourceAccessException e) {
            throw new ScreeningSourceUnavailableException("The identity provider is unreachable");
        } catch (RestClientException e) {
            // Another content type or malformed JSON from the token endpoint: fail closed as a 503, not a 500.
            throw new ScreeningSourceUnavailableException("The identity provider answered an unreadable token response");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
