package com.fintechplatform.paycore.limits.exception;

public class BalanceLimitExceededException extends RuntimeException {

    public BalanceLimitExceededException(String message) {
        super(message);
    }
}
