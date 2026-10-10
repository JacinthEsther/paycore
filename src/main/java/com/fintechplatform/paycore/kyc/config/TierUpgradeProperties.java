package com.fintechplatform.paycore.kyc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "paycore.kyc.tier-upgrade")
public class TierUpgradeProperties {

    private int reviewWorkingDays = 3;

    private boolean autoApprove = true;

    private Duration checkInterval = Duration.ofMinutes(15);

    private int recentRejectionDays = 30;

    public int getReviewWorkingDays() {
        return reviewWorkingDays;
    }

    public void setReviewWorkingDays(int reviewWorkingDays) {
        if (reviewWorkingDays < 0) {
            throw new IllegalArgumentException("paycore.kyc.tier-upgrade.review-working-days must not be negative");
        }
        this.reviewWorkingDays = reviewWorkingDays;
    }

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public void setAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }

    public Duration getCheckInterval() {
        return checkInterval;
    }

    public void setCheckInterval(Duration checkInterval) {
        this.checkInterval = checkInterval;
    }

    public int getRecentRejectionDays() {
        return recentRejectionDays;
    }

    public void setRecentRejectionDays(int recentRejectionDays) {
        this.recentRejectionDays = recentRejectionDays;
    }
}
