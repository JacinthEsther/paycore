package com.fintechplatform.paycore.identity.exception;

public class InvalidRefreshTokenException
        extends AuthenticationException {

    public InvalidRefreshTokenException() {
        super("Refresh token is invalid or expired");
    }
}
