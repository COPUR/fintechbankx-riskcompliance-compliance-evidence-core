package com.bank.compliance.infrastructure.web;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.infrastructure.config.ServiceCallerPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Who attests the facts of a screening request, from the caller's verified
 * token: a listed SERVICE client attests as CALLER_ATTESTED under its azp;
 * compliance staff (COMPLIANCE_OFFICER, ADMIN) as STAFF_ATTESTED under the
 * token's sub, the Keycloak user id. The principal name and any customer_id
 * claim are not used.
 */
@Component
public class CallerAttestation {

    private static final Set<String> STAFF_ROLES = Set.of("ROLE_COMPLIANCE_OFFICER", "ROLE_ADMIN");

    private final ServiceCallerPolicy serviceCallers;

    public CallerAttestation(ServiceCallerPolicy serviceCallers) {
        this.serviceCallers = serviceCallers;
    }

    public Attestation of(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("A screening needs an authenticated caller");
        }
        if (hasAny(token, Set.of("ROLE_SERVICE")) && serviceCallers.allowed(token)) {
            return Attestation.byService(token.getToken().getClaimAsString("azp"));
        }
        if (hasAny(token, STAFF_ROLES)) {
            return Attestation.byStaff(token.getToken().getSubject());
        }
        throw new AccessDeniedException("This caller may not attest screening facts");
    }

    private static boolean hasAny(Authentication authentication, Set<String> roles) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(roles::contains);
    }
}
