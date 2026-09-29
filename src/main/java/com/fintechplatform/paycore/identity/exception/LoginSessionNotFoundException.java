
package com.fintechplatform.paycore.identity.exception;

public class LoginSessionNotFoundException
        extends RuntimeException {

    public LoginSessionNotFoundException() {
        super("Login session not found");
    }
}


