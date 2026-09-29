package com.fintechplatform.paycore.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Login session lifetime, following common banking practice:
 *
 * <ul>
 *   <li>{@code expiration}: absolute limit. After it the customer must
 *       sign in again however active they are.</li>
 *   <li>{@code idleTimeout}: the session ends when it has not been used
 *       to issue tokens (login or refresh) for this long. Active clients
 *       refresh at least once per access-token lifetime, so keep the
 *       access token well below this.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "paycore.security.session")
public class SessionProperties {

    private Duration expiration = Duration.ofHours(12);

    private Duration idleTimeout = Duration.ofMinutes(15);

    public Duration getExpiration() {
        return expiration;
    }

    public void setExpiration(Duration expiration) {
        this.expiration = expiration;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }
}
