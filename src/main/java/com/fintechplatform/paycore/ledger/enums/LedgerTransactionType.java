package com.fintechplatform.paycore.ledger.enums;

/**
 * What a ledger transaction is. Customer accounts are always balanced
 * against another customer account (TRANSFER) or against PayCore's
 * settlement account, the real money PayCore holds at its bank:
 *
 * <pre>
 * TRANSFER           DEBIT sender customer    CREDIT recipient customer
 * DEPOSIT            DEBIT settlement         CREDIT customer      card top-up
 * INBOUND_TRANSFER   DEBIT settlement         CREDIT customer      from another bank
 * OUTBOUND_TRANSFER  DEBIT customer           CREDIT settlement    to another bank
 * ADJUSTMENT         either way against settlement, maker-checker approved
 * REVERSAL           the original's entries with the sides swapped
 * </pre>
 *
 * WITHDRAWAL is no longer posted: it remains for transactions recorded
 * while staff could still pay money out by hand.
 */
public enum LedgerTransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    REVERSAL,
    INBOUND_TRANSFER,
    OUTBOUND_TRANSFER,
    ADJUSTMENT
}
