package com.fintechplatform.paycore.kyc.exception;

import com.fintechplatform.paycore.kyc.enums.KycVerificationType;

import java.time.Instant;

/**
 * Too many FAILED identity-number checks (BVN, or NIN) in the rolling
 * window. {@code retryAfter} is when the oldest counted failure leaves
 * the window.
 */
public class BvnAttemptLimitExceededException extends RuntimeException {

    private final Instant retryAfter;
    private final KycVerificationType verificationType;

    public BvnAttemptLimitExceededException(Instant retryAfter) {
        this(retryAfter, KycVerificationType.BVN);
    }

    public BvnAttemptLimitExceededException(
            Instant retryAfter,
            KycVerificationType verificationType
    ) {
        super("Too many failed " + verificationType
                + " verification attempts; try again later");
        this.retryAfter = retryAfter;
        this.verificationType = verificationType;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    public KycVerificationType getVerificationType() {
        return verificationType;
    }
}
