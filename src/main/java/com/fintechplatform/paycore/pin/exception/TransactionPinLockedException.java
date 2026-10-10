package com.fintechplatform.paycore.pin.exception;

public class TransactionPinLockedException extends RuntimeException {

    public TransactionPinLockedException() {
        super("Your transaction PIN is locked after too many wrong attempts. Reset it to continue.");
    }
}
