package com.fintechplatform.paycore.pin.exception;

public class InvalidTransactionPinException extends RuntimeException {

    private final int attemptsRemaining;

    public InvalidTransactionPinException(int attemptsRemaining) {
        super("Wrong transaction PIN. " + attemptsRemaining
                + (attemptsRemaining == 1 ? " attempt" : " attempts") + " left before it locks.");
        this.attemptsRemaining = attemptsRemaining;
    }

    public int getAttemptsRemaining() {
        return attemptsRemaining;
    }
}
