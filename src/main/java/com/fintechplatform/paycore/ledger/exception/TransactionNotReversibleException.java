package com.fintechplatform.paycore.ledger.exception;

public class TransactionNotReversibleException extends RuntimeException {

    public TransactionNotReversibleException(String message) {
        super(message);
    }
}
