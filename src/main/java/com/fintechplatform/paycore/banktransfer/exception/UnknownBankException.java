package com.fintechplatform.paycore.banktransfer.exception;

public class UnknownBankException extends RuntimeException {

    public UnknownBankException(String bankCode) {
        super("Unknown bank: " + bankCode);
    }
}
