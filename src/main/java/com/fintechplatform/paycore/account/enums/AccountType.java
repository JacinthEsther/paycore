package com.fintechplatform.paycore.account.enums;

/**
 * Account products, plus PayCore's own system accounts.
 *
 * PERSONAL is the one customer product for now; BUSINESS, SAVINGS and so
 * on are added when there is a product behind them.
 *
 * SETTLEMENT is a system account: the pool of real money PayCore holds at
 * its settlement bank, one per currency, on the other side of every deposit
 * and withdrawal. It has no owner and no account number, and customers
 * cannot open one.
 */
public enum AccountType {
    PERSONAL,
    SETTLEMENT;

    public boolean isSystem() {
        return this == SETTLEMENT;
    }
}
