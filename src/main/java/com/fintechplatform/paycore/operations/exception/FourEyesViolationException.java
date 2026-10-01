package com.fintechplatform.paycore.operations.exception;

/** The officer who made a request tried to decide it. */
public class FourEyesViolationException extends RuntimeException {

    public FourEyesViolationException() {
        super("A request must be approved or rejected by a different officer from the one who made it");
    }
}
