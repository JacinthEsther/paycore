package com.fintechplatform.paycore.banktransfer.exception;

public class InboundNotificationLostException extends RuntimeException {

    private final String sessionId;

    public InboundNotificationLostException(String sessionId) {
        super("Test Bank sent the money, but the notification to PayCore was lost (simulated: amounts ending "
                + "in .95). Nothing is credited until reconciliation finds it.");
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
