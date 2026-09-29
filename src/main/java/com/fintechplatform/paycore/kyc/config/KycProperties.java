package com.fintechplatform.paycore.kyc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Limits failed BVN checks so the endpoint cannot be used to guess
 * other people's BVNs (or burn provider credit): at most
 * {@code maxFailedAttempts} FAILED results within any rolling
 * {@code attemptWindow}.
 */
@ConfigurationProperties(prefix = "paycore.kyc.bvn")
public class KycProperties {

    private int maxFailedAttempts = 3;

    private Duration attemptWindow = Duration.ofHours(24);

    /**
     * Per client network: at most {@code ipMaxAttempts} BVN checks (of
     * any result) within any rolling {@code ipAttemptWindow}, across all
     * customers. Stops one network from spreading guesses over many
     * accounts.
     */
    private int ipMaxAttempts = 10;

    private Duration ipAttemptWindow = Duration.ofHours(1);

    public int getIpMaxAttempts() {
        return ipMaxAttempts;
    }

    public void setIpMaxAttempts(int ipMaxAttempts) {
        if (ipMaxAttempts < 1) {
            throw new IllegalArgumentException(
                    "paycore.kyc.bvn.ip-max-attempts must be at least 1"
            );
        }
        this.ipMaxAttempts = ipMaxAttempts;
    }

    public Duration getIpAttemptWindow() {
        return ipAttemptWindow;
    }

    public void setIpAttemptWindow(Duration ipAttemptWindow) {
        if (ipAttemptWindow == null
                || ipAttemptWindow.isZero()
                || ipAttemptWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "paycore.kyc.bvn.ip-attempt-window must be positive"
            );
        }
        this.ipAttemptWindow = ipAttemptWindow;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        if (maxFailedAttempts < 1) {
            throw new IllegalArgumentException(
                    "paycore.kyc.bvn.max-failed-attempts must be at least 1"
            );
        }
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public Duration getAttemptWindow() {
        return attemptWindow;
    }

    public void setAttemptWindow(Duration attemptWindow) {
        if (attemptWindow == null
                || attemptWindow.isZero()
                || attemptWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "paycore.kyc.bvn.attempt-window must be positive"
            );
        }
        this.attemptWindow = attemptWindow;
    }
}
