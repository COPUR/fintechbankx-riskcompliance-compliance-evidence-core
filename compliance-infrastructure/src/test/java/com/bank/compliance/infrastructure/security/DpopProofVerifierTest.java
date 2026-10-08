package com.bank.compliance.infrastructure.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DpopProofVerifierTest {

    static final Instant NOW = Instant.parse("2026-10-08T06:00:00Z");
    static final String URI = "https://api.fintechbankx.example/api/v1/compliance/screenings/PAY-42";
    static final String TOKEN = "access-token-value";

    private final DpopProofVerifier verifier =
        new DpopProofVerifier(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
    private final ECKey key = newKey();

    @Test
    void aProofSignedByTheBoundKeyForThisRequestIsAccepted() {
        String proof = proof(key, claims -> { });

        assertThatCode(() -> verifier.verify(proof, "GET", URI + "?x=1", TOKEN, DpopProofVerifier.thumbprint(key)))
            .doesNotThrowAnyException();
    }

    @Test
    void aReplayedProofIsRefused() {
        String proof = proof(key, claims -> { });
        verifier.verify(proof, "GET", URI, TOKEN, null);

        assertThatThrownBy(() -> verifier.verify(proof, "GET", URI, TOKEN, null))
            .isInstanceOf(InvalidDpopProofException.class).hasMessageContaining("jti");
    }

    @Test
    void aProofByAnotherKeyThanTheTokenIsBoundToIsRefused() {
        String proof = proof(newKey(), claims -> { });

        assertThatThrownBy(() -> verifier.verify(proof, "GET", URI, TOKEN, DpopProofVerifier.thumbprint(key)))
            .isInstanceOf(InvalidDpopProofException.class).hasMessageContaining("cnf.jkt");
    }

    @Test
    void wrongMethodUriTokenHashOrStaleIatAreRefused() {
        assertRefused(proof(key, c -> c.claim("htm", "POST")), "htm");
        assertRefused(proof(key, c -> c.claim("htu", "https://elsewhere.example/api")), "htu");
        assertRefused(proof(key, c -> c.claim("ath", DpopProofVerifier.sha256("other-token"))), "ath");
        assertRefused(proof(key, c -> c.issueTime(Date.from(NOW.minusSeconds(120)))), "iat");
        assertRefused(proof(key, c -> c.issueTime(Date.from(NOW.plusSeconds(120)))), "iat");
        assertRefused(proof(key, c -> c.claim("jti", null)), "jti");
    }

    @Test
    void malformedOrUnsafeProofsAreRefused() throws Exception {
        assertRefused("not-a-jwt", "signed JWT");

        SignedJWT wrongType = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT)
            .jwk(key.toPublicJWK()).build(), baseClaims().build());
        wrongType.sign(new ECDSASigner(key));
        assertRefused(wrongType.serialize(), "typ");

        SignedJWT symmetric = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
            .type(DpopProofVerifier.DPOP_TYPE).build(), baseClaims().build());
        symmetric.sign(new MACSigner("0123456789abcdef0123456789abcdef"));
        assertRefused(symmetric.serialize(), "alg");

        SignedJWT noKey = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(DpopProofVerifier.DPOP_TYPE).build(), baseClaims().build());
        noKey.sign(new ECDSASigner(key));
        assertRefused(noKey.serialize(), "jwk");

        SignedJWT tampered = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(DpopProofVerifier.DPOP_TYPE).jwk(newKey().toPublicJWK()).build(), baseClaims().build());
        tampered.sign(new ECDSASigner(key));
        assertRefused(tampered.serialize(), "signature");
    }

    private void assertRefused(String proof, String check) {
        assertThatThrownBy(() -> verifier.verify(proof, "GET", URI, TOKEN, null))
            .isInstanceOf(InvalidDpopProofException.class).hasMessageContaining(check);
    }

    static String proof(ECKey signingKey, Consumer<JWTClaimsSet.Builder> customise) {
        try {
            JWTClaimsSet.Builder claims = baseClaims();
            customise.accept(claims);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(DpopProofVerifier.DPOP_TYPE).jwk(signingKey.toPublicJWK()).build(), claims.build());
            jwt.sign(new ECDSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .claim("htm", "GET")
            .claim("htu", URI)
            .claim("ath", DpopProofVerifier.sha256(TOKEN))
            .issueTime(Date.from(NOW));
    }

    static ECKey newKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
