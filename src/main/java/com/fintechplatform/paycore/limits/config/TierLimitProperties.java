package com.fintechplatform.paycore.limits.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "paycore.limits")
public class TierLimitProperties {

    private TierLimit tier1 = new TierLimit(new BigDecimal("50000"), new BigDecimal("300000"));
    private TierLimit tier2 = new TierLimit(new BigDecimal("200000"), new BigDecimal("500000"));
    private TierLimit tier3 = new TierLimit(new BigDecimal("5000000"), null);

    public TierLimit forTier(int tier) {
        return switch (tier) {
            case 1 -> tier1;
            case 2 -> tier2;
            case 3 -> tier3;
            default -> new TierLimit(BigDecimal.ZERO, BigDecimal.ZERO);
        };
    }

    public TierLimit getTier1() {
        return tier1;
    }

    public void setTier1(TierLimit tier1) {
        this.tier1 = tier1;
    }

    public TierLimit getTier2() {
        return tier2;
    }

    public void setTier2(TierLimit tier2) {
        this.tier2 = tier2;
    }

    public TierLimit getTier3() {
        return tier3;
    }

    public void setTier3(TierLimit tier3) {
        this.tier3 = tier3;
    }

    public static class TierLimit {

        private BigDecimal dailySend;

        private BigDecimal maxBalance;

        public TierLimit() {
        }

        public TierLimit(BigDecimal dailySend, BigDecimal maxBalance) {
            this.dailySend = dailySend;
            this.maxBalance = maxBalance;
        }

        public BigDecimal getDailySend() {
            return dailySend;
        }

        public void setDailySend(BigDecimal dailySend) {
            this.dailySend = dailySend;
        }

        public BigDecimal getMaxBalance() {
            return maxBalance;
        }

        public void setMaxBalance(BigDecimal maxBalance) {
            this.maxBalance = maxBalance;
        }
    }
}
