package com.fintechplatform.paycore.account.enums;

public enum AccountType {
    PERSONAL,
    SETTLEMENT;

    public boolean isSystem() {
        return this == SETTLEMENT;
    }
}
