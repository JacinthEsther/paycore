package com.fintechplatform.paycore.customer.exception;

public class EmailAlreadyVerifiedException extends RuntimeException {

    public EmailAlreadyVerifiedException() {
        super("Your email address is already verified");
    }
}
