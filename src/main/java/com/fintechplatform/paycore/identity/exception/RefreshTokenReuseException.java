package com.fintechplatform.paycore.identity.exception;

public class RefreshTokenReuseException
        extends AuthenticationException {

    public RefreshTokenReuseException() {
        super("Refresh token reuse detected; please log in again");
    }
}
