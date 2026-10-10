package com.fintechplatform.paycore.verification.enums;

public enum OneTimeCodePurpose {

    DEVICE_BINDING("confirm a new device"),

    PIN_RESET("reset your transaction PIN");

    private final String action;

    OneTimeCodePurpose(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
