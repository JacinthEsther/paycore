package com.fintechplatform.paycore.identity.exception;

public class PasswordIdentityNotFoundException extends RuntimeException {

    public PasswordIdentityNotFoundException() {
        super("Password identity not found");
    }
}
