package com.fintechplatform.paycore.kyc.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dojah credentials come from DOJAH_APP_ID / DOJAH_SECRET_KEY and must
 * never be committed. Base URL defaults to the free Sandbox.
 */
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
