package com.fintechplatform.paycore.identity.exception;

public class IdentityDisabledException
        extends AuthenticationException {

    public IdentityDisabledException() {
        super("Authentication identity is disabled");
    }
}

