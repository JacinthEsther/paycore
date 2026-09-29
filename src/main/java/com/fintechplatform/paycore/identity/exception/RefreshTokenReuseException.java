package com.fintechplatform.paycore.identity.exception;

/**
 * A refresh token that was already rotated or revoked was presented
 * again. The whole token family and its session have been revoked, so
 * the customer must re-authenticate.
 */
public class RefreshTokenReuseException
        extends AuthenticationException {

    public RefreshTokenReuseException() {
        super("Refresh token reuse detected; please log in again");
    }
}
