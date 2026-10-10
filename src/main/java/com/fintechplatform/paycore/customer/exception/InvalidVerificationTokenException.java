package com.fintechplatform.paycore.customer.exception;

public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("This verification link is invalid or has expired. Ask for a new one.");
    }
}
