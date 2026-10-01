package com.fintechplatform.paycore.ledger.exception;

public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String expected, String actual) {
        super("Currency mismatch: the account is in " + expected + " but the transfer is in " + actual);
    }
}
