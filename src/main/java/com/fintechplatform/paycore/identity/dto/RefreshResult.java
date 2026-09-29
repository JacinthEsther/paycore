package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.security.AccessToken;

public record RefreshResult(
        AccessToken accessToken,
        IssuedRefreshToken refreshToken
) {
}
