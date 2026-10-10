package com.fintechplatform.paycore.pin.exception;

public class TransactionPinAlreadySetException extends RuntimeException {

    public TransactionPinAlreadySetException() {
        super("You already have a transaction PIN. Change it or reset it instead.");
    }
}
