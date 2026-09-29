package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.customer.enums.CustomerStatus;

import java.time.Instant;
import java.util.UUID;

public record LoginResponse(
        UUID customerId,
        String email,
        CustomerStatus status,
        UUID sessionId,
        String sessionToken,
        Instant sessionExpiresAt,
        long sessionIdleTimeoutSeconds,
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String message
) {
}
