package com.fintechplatform.paycore.security;

import java.time.Instant;

public record AccessToken(
        String value,
        Instant expiresAt
) {
}
