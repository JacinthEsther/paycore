package com.fintechplatform.paycore.bills.exception;

public class UnknownBillerException extends RuntimeException {

    public UnknownBillerException(String billerId) {
        super("Unknown biller: " + billerId);
    }
}
