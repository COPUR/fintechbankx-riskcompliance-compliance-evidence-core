package com.bank.compliance.infrastructure.security;

import com.nimbusds.jose.jwk.ECKey;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;

import static com.bank.compliance.infrastructure.security.DpopProofVerifierTest.NOW;
import static com.bank.compliance.infrastructure.security.DpopProofVerifierTest.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DpopEnforcementFilterTest {

    private final ECKey key = DpopProofVerifierTest.newKey();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aBoundTokenWithAValidProofPasses() throws Exception {
        authenticate(Map.of("jkt", DpopProofVerifier.thumbprint(key)));
        MockHttpServletRequest request = request("DPoP " + TOKEN);
        request.addHeader("DPoP", DpopProofVerifierTest.proof(key, c -> { }));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(false).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aBoundTokenWithoutAProofIsRefused() throws Exception {
        authenticate(Map.of("jkt", DpopProofVerifier.thumbprint(key)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(false).doFilter(request("Bearer " + TOKEN), response, chain);

        assertRefused(response);
    }

    @Test
    void anUnboundBearerTokenPassesUntilDpopIsRequired() throws Exception {
        authenticate(null);
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter(false).doFilter(request("Bearer " + TOKEN), allowed, chain);
        assertThat(allowed.getStatus()).isEqualTo(200);

        authenticate(null);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter(true).doFilter(request("Bearer " + TOKEN), refused, mock(FilterChain.class));
        assertThat(refused.getStatus()).isEqualTo(401);
    }

    @Test
    void anUnboundTokenWithAProofIsRefusedWhenDpopIsRequired() throws Exception {
        authenticate(null);
        MockHttpServletRequest request = request("DPoP " + TOKEN);
        request.addHeader("DPoP", DpopProofVerifierTest.proof(key, c -> { }));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, chain);

        assertRefused(response);
    }

    @Test
    void theDpopSchemeWithoutAProofIsRefused() throws Exception {
        authenticate(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(false).doFilter(request("DPoP " + TOKEN), response, chain);

        assertRefused(response);
    }

    @Test
    void unauthenticatedRequestsAreLeftToTheAuthorizationRules() throws Exception {
        MockHttpServletRequest request = request(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(true).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private void assertRefused(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("DPoP error=\"invalid_dpop_proof\"");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    private static DpopEnforcementFilter filter(boolean required) {
        return new DpopEnforcementFilter(
            new DpopProofVerifier(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60)), required);
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/compliance/screenings/PAY-42");
        request.setScheme("https");
        request.setServerName("api.fintechbankx.example");
        request.setServerPort(443);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static void authenticate(Map<String, Object> cnf) {
        Jwt.Builder jwt = Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject("user-1");
        if (cnf != null) {
            jwt.claim("cnf", cnf);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
    }
}
