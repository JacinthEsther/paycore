package com.fintechplatform.paycore.customer.exception;

import java.time.Instant;

public class VerificationEmailRateLimitException extends RuntimeException {

    private final Instant retryAfter;

    public VerificationEmailRateLimitException(Instant retryAfter) {
        super("Too many verification emails; try again later");
        this.retryAfter = retryAfter;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }
}
