package com.fintechplatform.paycore.ledger.exception;

/**
 * The transaction is already reversed, or is itself a reversal. A reversal
 * is never reversed: if one was a mistake, post a new transaction instead.
 */
public class TransactionNotReversibleException extends RuntimeException {

    public TransactionNotReversibleException(String message) {
        super(message);
    }
}
