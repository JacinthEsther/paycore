package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.identity.entity.RefreshToken;

public record IssuedRefreshToken(
        String rawToken,
        RefreshToken refreshToken
) {
}
