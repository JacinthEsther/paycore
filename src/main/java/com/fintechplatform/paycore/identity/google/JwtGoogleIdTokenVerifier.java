package com.fintechplatform.paycore.identity.google;

import com.fintechplatform.paycore.identity.exception.InvalidGoogleTokenException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Set;

/**
 * Verifies Google ID tokens with Spring Security's JWT support: the RS256
 * signature against Google's published keys, then the issuer, the audience
 * (PayCore's client id) and the expiry. This is what Google documents for
 * back-end sign-in; no call to Google per sign-in beyond the cached keys.
 */
public class JwtGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final JwtDecoder decoder;

    public JwtGoogleIdTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** The production verifier, reading keys from Google. */
    public static JwtGoogleIdTokenVerifier forGoogle(GoogleProperties properties) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
        decoder.setJwtValidator(validator(properties.getClientId()));

        return new JwtGoogleIdTokenVerifier(decoder);
    }

    /**
     * Issuer, audience and expiry checks; public so tests can apply them
     * to tokens signed with a local key.
     */
    public static OAuth2TokenValidator<Jwt> validator(String clientId) {

        // Read as a string: Google also issues "accounts.google.com" without
        // a scheme, which Jwt.getIssuer() fails to parse as a URL.
        OAuth2TokenValidator<Jwt> issuer = jwt ->
                ISSUERS.contains(jwt.getClaimAsString("iss"))
                        ? OAuth2TokenValidatorResult.success()
                        : failure("The token was not issued by Google");

        OAuth2TokenValidator<Jwt> audience = jwt ->
                jwt.getAudience() != null && jwt.getAudience().contains(clientId)
                        ? OAuth2TokenValidatorResult.success()
                        : failure("The token was issued for a different application");

        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, audience);
    }

    @Override
    public GoogleIdentity verify(String idToken) {

        Jwt jwt;

        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException exception) {
            throw new InvalidGoogleTokenException("The Google sign-in could not be verified");
        }

        String email = jwt.getClaimAsString("email");

        if (jwt.getSubject() == null || email == null) {
            throw new InvalidGoogleTokenException("The Google sign-in did not include an email address");
        }

        return new GoogleIdentity(
                jwt.getSubject(),
                email,
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")
        );
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
