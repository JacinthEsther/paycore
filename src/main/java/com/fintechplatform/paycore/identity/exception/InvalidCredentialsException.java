package com.fintechplatform.paycore.identity.exception;

public class InvalidCredentialsException
        extends AuthenticationException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}

