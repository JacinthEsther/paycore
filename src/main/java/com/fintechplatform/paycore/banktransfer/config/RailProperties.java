package com.fintechplatform.paycore.banktransfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "paycore.rails")
public class RailProperties {

    private String webhookSecret;

    private BigDecimal simulatorInboundLimitPerAccount = new BigDecimal("1000000");

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public BigDecimal getSimulatorInboundLimitPerAccount() {
        return simulatorInboundLimitPerAccount;
    }

    public void setSimulatorInboundLimitPerAccount(BigDecimal limit) {
        if (limit == null || limit.signum() <= 0 || limit.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "paycore.rails.simulator-inbound-limit-per-account must be positive, with at most 2 decimal places"
            );
        }
        this.simulatorInboundLimitPerAccount = limit;
    }
}
