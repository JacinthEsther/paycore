package com.fintechplatform.paycore.ledger.exception;

public class LedgerTransactionNotFoundException extends RuntimeException {

    public LedgerTransactionNotFoundException() {
        super("Transaction not found");
    }
}
