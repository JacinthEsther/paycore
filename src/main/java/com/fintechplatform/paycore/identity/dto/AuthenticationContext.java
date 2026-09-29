package com.fintechplatform.paycore.identity.dto;

public record AuthenticationContext(
        String ipAddress,
        String userAgent
) {
}
