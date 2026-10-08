package com.bank.compliance.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Reads the access token from {@code Authorization: Bearer <token>} or, for
 * DPoP-bound tokens, {@code Authorization: DPoP <token>} (RFC 9449, section 7.1).
 */
public class DpopAwareBearerTokenResolver implements BearerTokenResolver {

    private static final String DPOP_PREFIX = "dpop ";
    private final DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (usesDpopScheme(request)) {
            String token = request.getHeader(HttpHeaders.AUTHORIZATION).substring(DPOP_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return bearer.resolve(request);
    }

    static boolean usesDpopScheme(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.regionMatches(true, 0, DPOP_PREFIX, 0, DPOP_PREFIX.length());
    }
}
