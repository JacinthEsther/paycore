package com.fintechplatform.paycore.operations.exception;

public class FourEyesViolationException extends RuntimeException {

    public FourEyesViolationException() {
        super("A request must be approved or rejected by a different officer from the one who made it");
    }
}
