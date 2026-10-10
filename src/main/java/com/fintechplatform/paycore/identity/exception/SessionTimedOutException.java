package com.fintechplatform.paycore.identity.exception;

public class SessionTimedOutException extends RuntimeException {

    public enum Reason {
        IDLE,
        EXPIRED
    }

    private final Reason reason;

    public SessionTimedOutException(Reason reason) {
        super(reason == Reason.IDLE
                ? "Session ended after a period of inactivity; please sign in again"
                : "Session reached its maximum duration; please sign in again");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
