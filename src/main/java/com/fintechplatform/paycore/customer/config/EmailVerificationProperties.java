package com.fintechplatform.paycore.customer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "paycore.email-verification")
public class EmailVerificationProperties {

    /**
     * Where the web app runs: verification links point to
     * {publicUrl}/verify-email?token=...
     */
    private String publicUrl = "http://localhost:5173";

    /** How long a link works. */
    private Duration tokenTtl = Duration.ofHours(24);

    /** Minimum wait between two links for the same customer. */
    private Duration resendCooldown = Duration.ofSeconds(60);

    /** Most links one customer can be sent in 24 hours. */
    private int maxPerDay = 5;

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }

    public int getMaxPerDay() {
        return maxPerDay;
    }

    public void setMaxPerDay(int maxPerDay) {
        this.maxPerDay = maxPerDay;
    }
}
