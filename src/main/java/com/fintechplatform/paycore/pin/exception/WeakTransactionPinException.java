package com.fintechplatform.paycore.pin.exception;

public class WeakTransactionPinException extends RuntimeException {

    public WeakTransactionPinException() {
        super("Choose a PIN that is harder to guess: not one digit repeated and not a run like 1234 or 9876");
    }
}
