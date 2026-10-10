package com.fintechplatform.paycore.reconciliation.enums;

public enum ReconciliationResult {

    MATCHED,
    MISSING_LOCAL,
    MISSING_PROVIDER,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH;

    public boolean isBreak() {
        return this != MATCHED;
    }
}
