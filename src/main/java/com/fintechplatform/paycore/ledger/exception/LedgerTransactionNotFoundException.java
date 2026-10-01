package com.fintechplatform.paycore.ledger.exception;

/**
 * Also thrown when the transaction exists but touches none of the
 * caller's accounts, so transaction ids and references cannot be probed.
 */
public class LedgerTransactionNotFoundException extends RuntimeException {

    public LedgerTransactionNotFoundException() {
        super("Transaction not found");
    }
}
