package com.fintechplatform.paycore.banktransfer.exception;

/** The notification was not signed with PayCore's webhook secret. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
