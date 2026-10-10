package com.fintechplatform.paycore.pin.exception;

public class TransactionPinRequiredException extends RuntimeException {

    public TransactionPinRequiredException() {
        super("Enter your transaction PIN (the X-Transaction-PIN header)");
    }
}
