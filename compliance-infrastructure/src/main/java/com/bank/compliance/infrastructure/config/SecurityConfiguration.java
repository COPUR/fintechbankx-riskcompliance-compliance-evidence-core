package com.bank.compliance.infrastructure.config;

import com.bank.compliance.infrastructure.security.DpopAwareBearerTokenResolver;
import com.bank.compliance.infrastructure.security.DpopEnforcementFilter;
import com.bank.compliance.infrastructure.security.DpopProofVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stateless OAuth2 resource server. Tokens come from the platform Keycloak
 * realm and must name this service in {@code aud}
 * (spring.security.oauth2.resourceserver.jwt.audiences); realm roles become
 * ROLE_* authorities for the @PreAuthorize rules on ComplianceController.
 * DPoP-bound tokens need a valid proof, and security.dpop.required refuses
 * plain bearer tokens once every caller sends DPoP. Actuator endpoints are
 * served on the management port, which the mesh policy opens only to the
 * observability namespace.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    @Value("${security.dpop.required:false}") boolean dpopRequired,
                                    @Value("${security.dpop.proof-window:PT60S}") Duration proofWindow) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(new DpopAwareBearerTokenResolver())
                .jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles())))
            .addFilterAfter(new DpopEnforcementFilter(new DpopProofVerifier(Clock.systemUTC(), proofWindow), dpopRequired),
                BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> new JwtAuthenticationToken(jwt, realmRoles(jwt), jwt.getSubject());
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
            .map(String::valueOf)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
            .toList();
    }
}
