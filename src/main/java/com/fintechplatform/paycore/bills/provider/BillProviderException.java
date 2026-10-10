package com.fintechplatform.paycore.bills.provider;

public class BillProviderException extends RuntimeException {

    public BillProviderException(String message) {
        super(message);
    }

    public BillProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
