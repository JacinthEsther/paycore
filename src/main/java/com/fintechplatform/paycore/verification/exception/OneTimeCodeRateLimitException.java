package com.fintechplatform.paycore.verification.exception;

import java.time.Instant;

public class OneTimeCodeRateLimitException extends RuntimeException {

    private final Instant retryAfter;

    public OneTimeCodeRateLimitException(Instant retryAfter) {
        super("A code was sent recently. Wait before asking for another one.");
        this.retryAfter = retryAfter;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }
}
