package com.fintechplatform.paycore.limits.exception;

import com.fintechplatform.paycore.ledger.domain.Money;

import java.time.Instant;

public class SpendingLimitExceededException extends RuntimeException {

    private final String limit;
    private final Money remaining;
    private final Instant resetsAt;

    public SpendingLimitExceededException(String limit, String message, Money remaining, Instant resetsAt) {
        super(message);
        this.limit = limit;
        this.remaining = remaining;
        this.resetsAt = resetsAt;
    }

    public String getLimit() {
        return limit;
    }

    public Money getRemaining() {
        return remaining;
    }

    public Instant getResetsAt() {
        return resetsAt;
    }
}
