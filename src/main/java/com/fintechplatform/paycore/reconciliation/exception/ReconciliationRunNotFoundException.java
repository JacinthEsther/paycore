package com.fintechplatform.paycore.reconciliation.exception;

public class ReconciliationRunNotFoundException extends RuntimeException {

    public ReconciliationRunNotFoundException() {
        super("Reconciliation run not found");
    }
}
