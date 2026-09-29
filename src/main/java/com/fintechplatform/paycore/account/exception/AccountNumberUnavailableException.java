package com.fintechplatform.paycore.account.exception;

/**
 * Every generated account number was already taken. With 10^9 serials this
 * signals a broken generator or an exhausted number range, not bad luck.
 */
public class AccountNumberUnavailableException extends RuntimeException {

    public AccountNumberUnavailableException(int attempts) {
        super("Could not allocate a unique account number after " + attempts + " attempts");
    }
}
