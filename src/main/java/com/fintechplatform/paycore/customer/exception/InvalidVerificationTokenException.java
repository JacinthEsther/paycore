package com.fintechplatform.paycore.customer.exception;

/**
 * One message for unknown, used, replaced, expired and outdated links, so
 * the response says nothing about which tokens exist.
 */
public class InvalidVerificationTokenException extends RuntimeException {

    public InvalidVerificationTokenException() {
        super("This verification link is invalid or has expired. Ask for a new one.");
    }
}
