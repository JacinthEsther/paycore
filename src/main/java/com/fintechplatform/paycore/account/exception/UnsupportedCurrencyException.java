package com.fintechplatform.paycore.account.exception;

public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String currency) {
        super("Accounts cannot be opened in " + currency);
    }
}
