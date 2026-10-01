package com.fintechplatform.paycore.identity.google;

import com.fintechplatform.paycore.identity.exception.GoogleSignInDisabledException;
import com.fintechplatform.paycore.identity.exception.InvalidGoogleTokenException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same issuer, audience and expiry checks as production, on tokens
 * signed with a local key instead of Google's.
 */
class JwtGoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "paycore-test.apps.googleusercontent.com";

    private static RSAKey googleKey;
    private static RSAKey otherKey;
    private static JwtGoogleIdTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {

        googleKey = new RSAKeyGenerator(2048).keyID("google").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("attacker").generate();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(googleKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtGoogleIdTokenVerifier.validator(CLIENT_ID));

        verifier = new JwtGoogleIdTokenVerifier(decoder);
    }

    @Test
    void shouldReturnTheClaimsOfAValidToken() throws Exception {

        GoogleIdentity identity = verifier.verify(token(googleKey, claims -> { }));

        assertThat(identity).isEqualTo(
                new GoogleIdentity("1122334455", "ada@gmail.com", true, "Ada", "Obi")
        );
    }

    @Test
    void shouldAcceptBothGoogleIssuerSpellings() throws Exception {

        assertThat(verifier.verify(token(googleKey, claims -> claims.issuer("accounts.google.com"))))
                .isNotNull();
    }

    @Test
    void shouldReportAnUnverifiedEmail() throws Exception {

        assertThat(verifier.verify(token(googleKey, claims -> claims.claim("email_verified", false))).emailVerified())
                .isFalse();
    }

    @Test
    void shouldRejectATokenForAnotherApplication() throws Exception {

        assertThatThrownBy(() -> verifier.verify(token(googleKey, claims -> claims.audience("someone-else"))))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void shouldRejectATokenNotIssuedByGoogle() throws Exception {

        assertThatThrownBy(() -> verifier.verify(token(googleKey, claims -> claims.issuer("https://evil.example"))))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void shouldRejectAnExpiredToken() throws Exception {

        String expired = token(googleKey, claims -> claims
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))));

        assertThatThrownBy(() -> verifier.verify(expired))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void shouldRejectATokenSignedWithAnotherKey() throws Exception {

        assertThatThrownBy(() -> verifier.verify(token(otherKey, claims -> { })))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void shouldRejectGarbageAndATokenWithoutEmail() throws Exception {

        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(InvalidGoogleTokenException.class);

        assertThatThrownBy(() -> verifier.verify(token(googleKey, claims -> claims.claim("email", null))))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void shouldBeOffWithoutAClientId() {

        GoogleIdTokenVerifier disabled = new GoogleConfiguration().googleIdTokenVerifier(new GoogleProperties());

        assertThatThrownBy(() -> disabled.verify("anything"))
                .isInstanceOf(GoogleSignInDisabledException.class);
    }

    private static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customize) throws JOSEException {

        JWTClaimsSet.Builder claims =
                new JWTClaimsSet.Builder()
                        .issuer("https://accounts.google.com")
                        .audience(CLIENT_ID)
                        .subject("1122334455")
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                        .claim("email", "ada@gmail.com")
                        .claim("email_verified", true)
                        .claim("given_name", "Ada")
                        .claim("family_name", "Obi");

        customize.accept(claims);

        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims.build()
                );

        jwt.sign(new RSASSASigner(key));

        return jwt.serialize();
    }
}
