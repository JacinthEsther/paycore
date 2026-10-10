package com.fintechplatform.paycore.identity.google;

public record GoogleIdentity(
        String subject,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName
) {
}
