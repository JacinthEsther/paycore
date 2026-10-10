package com.fintechplatform.paycore.reconciliation.exception;

public class ReconciliationItemNotFoundException extends RuntimeException {

    public ReconciliationItemNotFoundException() {
        super("Reconciliation item not found");
    }
}
