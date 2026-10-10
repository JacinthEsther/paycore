package com.fintechplatform.paycore.verification.exception;

public class InvalidOneTimeCodeException extends RuntimeException {

    public InvalidOneTimeCodeException() {
        super("The code is wrong or has expired. Ask for a new one.");
    }
}
