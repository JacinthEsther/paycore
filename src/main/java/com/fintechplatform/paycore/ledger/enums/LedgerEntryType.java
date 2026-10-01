package com.fintechplatform.paycore.ledger.enums;

/**
 * The side of an entry. Amounts are always positive; the type carries the
 * direction. For a customer account a CREDIT adds to the balance and a
 * DEBIT takes from it.
 */
public enum LedgerEntryType {
    DEBIT,
    CREDIT
}
