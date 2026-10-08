package com.bank.compliance.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies a DPoP proof (RFC 9449, section 4.3) for one request: an explicit
 * {@code dpop+jwt} type, an asymmetric algorithm, a valid signature by the
 * public key in the header, matching {@code htm}/{@code htu}, a fresh
 * {@code iat}, an unused {@code jti}, the {@code ath} hash of the access
 * token and, when the token is sender-constrained, a {@code cnf.jkt} equal to
 * the proof key's thumbprint.
 *
 * Replayed {@code jti}s are remembered per pod only; the short acceptance
 * window keeps cross-pod replay to that window.
 */
public class DpopProofVerifier {

    static final JOSEObjectType DPOP_TYPE = new JOSEObjectType("dpop+jwt");
    private static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(
        JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
        JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
        JWSAlgorithm.RS256);

    private final Clock clock;
    private final Duration window;
    private final Map<String, Instant> seenJtis = new ConcurrentHashMap<>();

    public DpopProofVerifier(Clock clock, Duration window) {
        this.clock = clock;
        this.window = window;
    }

    /**
     * @param proof       value of the DPoP request header
     * @param method      HTTP method of the request
     * @param uri         request URI without query or fragment
     * @param accessToken the access token sent with the proof
     * @param boundJkt    the token's cnf.jkt claim, or null if the token is not bound
     * @throws InvalidDpopProofException when any check fails
     */
    public void verify(String proof, String method, String uri, String accessToken, String boundJkt) {
        SignedJWT jwt = parse(proof);
        if (!DPOP_TYPE.equals(jwt.getHeader().getType())) {
            throw new InvalidDpopProofException("typ must be dpop+jwt");
        }
        if (!ALLOWED_ALGORITHMS.contains(jwt.getHeader().getAlgorithm())) {
            throw new InvalidDpopProofException("alg must be asymmetric");
        }
        JWK key = jwt.getHeader().getJWK();
        if (key == null || key.isPrivate()) {
            throw new InvalidDpopProofException("jwk must be a public key");
        }
        verifySignature(jwt, key);

        JWTClaimsSet claims = claims(jwt);
        if (!method.equalsIgnoreCase(stringClaim(claims, "htm"))) {
            throw new InvalidDpopProofException("htm does not match the request method");
        }
        if (!stripQuery(uri).equals(stripQuery(stringClaim(claims, "htu")))) {
            throw new InvalidDpopProofException("htu does not match the request URI");
        }
        Date issuedAt = claims.getIssueTime();
        Instant now = clock.instant();
        if (issuedAt == null || issuedAt.toInstant().isBefore(now.minus(window))
                || issuedAt.toInstant().isAfter(now.plus(window))) {
            throw new InvalidDpopProofException("iat is outside the acceptance window");
        }
        if (!sha256(accessToken).equals(stringClaim(claims, "ath"))) {
            throw new InvalidDpopProofException("ath does not match the access token");
        }
        if (boundJkt != null && !boundJkt.equals(thumbprint(key))) {
            throw new InvalidDpopProofException("proof key does not match the token's cnf.jkt");
        }
        rememberJti(stringClaim(claims, "jti"), now);
    }

    private void rememberJti(String jti, Instant now) {
        seenJtis.values().removeIf(seenAt -> seenAt.isBefore(now.minus(window.multipliedBy(2))));
        if (seenJtis.putIfAbsent(jti, now) != null) {
            throw new InvalidDpopProofException("jti has already been used");
        }
    }

    private static SignedJWT parse(String proof) {
        try {
            return SignedJWT.parse(proof);
        } catch (ParseException e) {
            throw new InvalidDpopProofException("proof is not a signed JWT");
        }
    }

    private static JWTClaimsSet claims(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidDpopProofException("proof claims are not valid JSON");
        }
    }

    private static void verifySignature(SignedJWT jwt, JWK key) {
        try {
            JWSVerifier verifier = switch (key) {
                case ECKey ec -> new ECDSAVerifier(ec);
                case RSAKey rsa -> new RSASSAVerifier(rsa);
                default -> throw new InvalidDpopProofException("jwk type is not supported");
            };
            if (!jwt.verify(verifier)) {
                throw new InvalidDpopProofException("signature is not valid");
            }
        } catch (JOSEException | IllegalArgumentException e) {
            throw new InvalidDpopProofException("signature cannot be verified");
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidDpopProofException(name + " is missing");
        }
        return text;
    }

    private static String stripQuery(String uri) {
        int cut = uri.length();
        for (char c : new char[] {'?', '#'}) {
            int at = uri.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return uri.substring(0, cut);
    }

    static String thumbprint(JWK key) {
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new InvalidDpopProofException("jwk thumbprint cannot be computed");
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64URL.encode(digest).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
