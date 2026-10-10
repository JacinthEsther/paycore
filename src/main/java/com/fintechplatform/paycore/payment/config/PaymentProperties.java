package com.fintechplatform.paycore.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "paycore.payments")
public class PaymentProperties {

    private Duration staleAfter = Duration.ofMinutes(2);
    private Duration firstCheckAfter = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofMinutes(30);
    private int maxChecks = 12;
    private Duration notFoundGrace = Duration.ofMinutes(10);
    private int batchSize = 50;

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public void setStaleAfter(Duration staleAfter) {
        this.staleAfter = requireNotNegative(staleAfter, "stale-after");
    }

    public Duration getFirstCheckAfter() {
        return firstCheckAfter;
    }

    public void setFirstCheckAfter(Duration firstCheckAfter) {
        this.firstCheckAfter = requireNotNegative(firstCheckAfter, "first-check-after");
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = requireNotNegative(maxBackoff, "max-backoff");
    }

    public int getMaxChecks() {
        return maxChecks;
    }

    public void setMaxChecks(int maxChecks) {
        if (maxChecks < 1) {
            throw new IllegalArgumentException("paycore.payments.max-checks must be at least 1");
        }
        this.maxChecks = maxChecks;
    }

    public Duration getNotFoundGrace() {
        return notFoundGrace;
    }

    public void setNotFoundGrace(Duration notFoundGrace) {
        this.notFoundGrace = requireNotNegative(notFoundGrace, "not-found-grace");
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("paycore.payments.batch-size must be at least 1");
        }
        this.batchSize = batchSize;
    }

    public Duration backoffAfter(int checksMade) {

        Duration delay = firstCheckAfter;

        for (int i = 0; i < checksMade && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.multipliedBy(2);
        }

        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private static Duration requireNotNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException("paycore.payments." + name + " must not be negative");
        }
        return value;
    }
}
