package com.bank.compliance.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Runs after bearer-token authentication and enforces DPoP sender
 * constraining:
 * <ul>
 *   <li>a token bound to a key ({@code cnf.jkt}) is accepted only with a valid
 *       DPoP proof by that key and the {@code DPoP} authorization scheme;</li>
 *   <li>a request that sends a DPoP proof has it verified;</li>
 *   <li>with {@code required=true}, an unbound bearer token is refused.</li>
 * </ul>
 * Failures answer 401 with {@code WWW-Authenticate: DPoP error="invalid_dpop_proof"}.
 */
public class DpopEnforcementFilter extends OncePerRequestFilter {

    private final DpopProofVerifier verifier;
    private final boolean required;

    public DpopEnforcementFilter(DpopProofVerifier verifier, boolean required) {
        this.verifier = verifier;
        this.required = required;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            check(request, token.getToken());
        } catch (InvalidDpopProofException e) {
            SecurityContextHolder.clearContext();
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "DPoP error=\"invalid_dpop_proof\", error_description=\"" + e.getMessage() + "\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private void check(HttpServletRequest request, Jwt jwt) {
        String proof = request.getHeader("DPoP");
        String boundJkt = boundJkt(jwt);
        boolean dpopScheme = DpopAwareBearerTokenResolver.usesDpopScheme(request);

        if (proof == null) {
            if (boundJkt != null || dpopScheme) {
                throw new InvalidDpopProofException("DPoP proof is required for this token");
            }
            if (required) {
                throw new InvalidDpopProofException("DPoP-bound access token is required");
            }
            return;
        }
        if (boundJkt == null && required) {
            throw new InvalidDpopProofException("access token is not DPoP-bound");
        }
        verifier.verify(proof, request.getMethod(), request.getRequestURL().toString(), jwt.getTokenValue(), boundJkt);
    }

    static String boundJkt(Jwt jwt) {
        Object cnf = jwt.getClaims().get("cnf");
        if (cnf instanceof Map<?, ?> confirmation && confirmation.get("jkt") instanceof String jkt && !jkt.isBlank()) {
            return jkt;
        }
        return null;
    }
}
