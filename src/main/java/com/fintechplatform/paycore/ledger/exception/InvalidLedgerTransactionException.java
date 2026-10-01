package com.fintechplatform.paycore.ledger.exception;

/**
 * A transfer the client asked for that can never be valid, such as one
 * from an account to itself.
 */
public class InvalidLedgerTransactionException extends RuntimeException {

    public InvalidLedgerTransactionException(String message) {
        super(message);
    }
}
