package com.fintechplatform.paycore.kyc.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dojah")
public record DojahProperties(
        String baseUrl,
        String appId,
        String secretKey
) {

    public boolean hasCredentials() {
        return appId != null && !appId.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
