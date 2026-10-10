package com.fintechplatform.paycore.kyc.exception;

import com.fintechplatform.paycore.kyc.enums.KycVerificationType;

import java.time.Instant;

public class BvnIpRateLimitExceededException extends RuntimeException {

    private final Instant retryAfter;
    private final KycVerificationType verificationType;

    public BvnIpRateLimitExceededException(Instant retryAfter) {
        this(retryAfter, KycVerificationType.BVN);
    }

    public BvnIpRateLimitExceededException(
            Instant retryAfter,
            KycVerificationType verificationType
    ) {
        super("Too many verification attempts from this network; try again later");
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
