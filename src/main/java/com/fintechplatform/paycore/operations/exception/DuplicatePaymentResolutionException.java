package com.fintechplatform.paycore.operations.exception;

public class DuplicatePaymentResolutionException extends RuntimeException {

    public DuplicatePaymentResolutionException() {
        super("A resolution of this payment is already waiting for approval");
    }
}
