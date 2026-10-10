package com.fintechplatform.paycore.identity.dto;

public record AuthenticationContext(
        String ipAddress,
        String userAgent,
        String deviceId
) {

    public AuthenticationContext(String ipAddress, String userAgent) {
        this(ipAddress, userAgent, null);
    }
}
