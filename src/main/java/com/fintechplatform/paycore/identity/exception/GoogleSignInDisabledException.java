package com.fintechplatform.paycore.identity.exception;

public class GoogleSignInDisabledException extends RuntimeException {

    public GoogleSignInDisabledException() {
        super("Sign in with Google is not enabled on this server");
    }
}
