package com.fintechplatform.paycore.funding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "paycore.funding")
public class FundingProperties {

    private BigDecimal maxTotalPerAccount = new BigDecimal("500000");

    public BigDecimal getMaxTotalPerAccount() {
        return maxTotalPerAccount;
    }

    public void setMaxTotalPerAccount(BigDecimal maxTotalPerAccount) {
        if (maxTotalPerAccount == null
                || maxTotalPerAccount.signum() <= 0
                || maxTotalPerAccount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "paycore.funding.max-total-per-account must be positive, with at most 2 decimal places"
            );
        }
        this.maxTotalPerAccount = maxTotalPerAccount;
    }
}
