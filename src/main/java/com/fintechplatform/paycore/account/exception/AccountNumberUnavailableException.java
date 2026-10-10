package com.fintechplatform.paycore.account.exception;

public class AccountNumberUnavailableException extends RuntimeException {

    public AccountNumberUnavailableException(int attempts) {
        super("Could not allocate a unique account number after " + attempts + " attempts");
    }
}
