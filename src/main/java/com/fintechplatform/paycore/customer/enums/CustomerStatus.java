package com.fintechplatform.paycore.customer.enums;

public enum CustomerStatus {

    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean canAuthenticate() {
        return this != SUSPENDED && this != CLOSED;
    }
}
