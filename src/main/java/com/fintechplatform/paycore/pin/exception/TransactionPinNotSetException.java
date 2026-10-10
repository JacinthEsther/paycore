package com.fintechplatform.paycore.pin.exception;

public class TransactionPinNotSetException extends RuntimeException {

    public TransactionPinNotSetException() {
        super("Set a transaction PIN before moving money");
    }
}
