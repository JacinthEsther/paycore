package com.fintechplatform.paycore.identity.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign in with Google. Set paycore.google.client-id (PAYCORE_GOOGLE_CLIENT_ID)
 * to the OAuth client id of a "Web application" client in Google Cloud
 * Console to turn it on; empty (the default) leaves it off. No client
 * secret is needed: PayCore only verifies ID tokens.
 */
@ConfigurationProperties(prefix = "paycore.google")
public class GoogleProperties {

    private String clientId = "";

    /** Google's signing keys, fetched once and cached by the decoder. */
    private String jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";

    public boolean isEnabled() {
        return clientId != null && !clientId.isBlank();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }
}
