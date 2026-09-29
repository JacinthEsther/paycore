package com.fintechplatform.paycore.customer.enums;

public enum CustomerStatus {

    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    CLOSED;

    /**
     * Whether a customer in this status may sign in, refresh tokens or use
     * an access token they already hold.
     */
    public boolean canAuthenticate() {
        return this != SUSPENDED && this != CLOSED;
    }
}
