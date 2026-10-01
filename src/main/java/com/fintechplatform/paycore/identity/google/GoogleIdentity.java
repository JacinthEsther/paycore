package com.fintechplatform.paycore.identity.google;

/**
 * The verified claims of a Google ID token that PayCore uses.
 *
 * @param subject Google's stable, never-reused account id ("sub")
 */
public record GoogleIdentity(
        String subject,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName
) {
}
