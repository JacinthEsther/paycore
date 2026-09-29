package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.identity.entity.RefreshToken;

/**
 * The raw token goes to the client exactly once; only its hash is stored.
 */
public record IssuedRefreshToken(
        String rawToken,
        RefreshToken refreshToken
) {
}
