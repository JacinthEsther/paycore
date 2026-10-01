package com.fintechplatform.paycore.operations.exception;

public class DuplicateReversalRequestException extends RuntimeException {

    public DuplicateReversalRequestException() {
        super("A reversal of this transaction is already waiting for approval");
    }
}
