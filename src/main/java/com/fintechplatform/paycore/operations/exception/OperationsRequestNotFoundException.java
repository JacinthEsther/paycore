package com.fintechplatform.paycore.operations.exception;

public class OperationsRequestNotFoundException extends RuntimeException {

    public OperationsRequestNotFoundException() {
        super("Request not found");
    }
}
