package com.fintechplatform.paycore.identity.exception;

/**
 * No Google client id is configured (paycore.google.client-id).
 */
public class GoogleSignInDisabledException extends RuntimeException {

    public GoogleSignInDisabledException() {
        super("Sign in with Google is not enabled on this server");
    }
}
