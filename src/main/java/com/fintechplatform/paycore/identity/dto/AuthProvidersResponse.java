package com.fintechplatform.paycore.identity.dto;

public record AuthProvidersResponse(
        boolean password,
        Google google
) {

    public record Google(boolean enabled, String clientId) {
    }
}
