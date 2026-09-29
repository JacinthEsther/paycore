package com.fintechplatform.paycore.customer.exception;

public class DuplicateCustomerException
        extends RuntimeException {

    public DuplicateCustomerException(String message) {
        super(message);
    }
}