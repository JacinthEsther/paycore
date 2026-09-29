package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.security.AccessToken;

public record LoginResult(
        Customer customer,
        LoginSession session,
        String sessionToken,
        AccessToken accessToken,
        IssuedRefreshToken refreshToken
) {
}
