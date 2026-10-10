package com.fintechplatform.paycore.bills.exception;

public class BillCustomerNotFoundException extends RuntimeException {

    public BillCustomerNotFoundException() {
        super("The biller has no customer with that number. Check it and try again.");
    }
}
