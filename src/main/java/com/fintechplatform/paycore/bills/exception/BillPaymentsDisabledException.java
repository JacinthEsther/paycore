package com.fintechplatform.paycore.bills.exception;

public class BillPaymentsDisabledException extends RuntimeException {

    public BillPaymentsDisabledException() {
        super("Bill payments are not available right now");
    }
}
