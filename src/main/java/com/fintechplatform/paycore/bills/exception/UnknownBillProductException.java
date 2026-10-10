package com.fintechplatform.paycore.bills.exception;

public class UnknownBillProductException extends RuntimeException {

    public UnknownBillProductException(String productCode) {
        super("That product is not available: " + productCode);
    }
}
