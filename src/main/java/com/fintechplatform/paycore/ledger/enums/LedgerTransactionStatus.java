package com.fintechplatform.paycore.ledger.enums;

/**
 * <pre>
 * INITIATED --post--> POSTED --reverse--> REVERSED
 * </pre>
 *
 * INITIATED does not mean money moved. POSTED means the balanced entries
 * are committed. REVERSED means a later, compensating transaction undid
 * the effect; the original entries stay untouched.
 */
public enum LedgerTransactionStatus {
    INITIATED,
    POSTED,
    REVERSED
}
