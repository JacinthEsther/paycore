package com.fintechplatform.paycore.ledger.exception;

/**
 * The idempotency key was already used for a different request. A retry
 * must repeat the original request exactly; anything else needs a new key.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("This idempotency key was already used for a different request");
    }
}
