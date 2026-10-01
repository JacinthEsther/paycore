package com.fintechplatform.paycore.identity.dto;

/**
 * Which ways to sign in this server offers, so the UI only shows what
 * works. The Google client id is public by design: it is in every page
 * that shows a Google button.
 */
public record AuthProvidersResponse(
        boolean password,
        Google google
) {

    public record Google(boolean enabled, String clientId) {
    }
}
