package com.fintechplatform.paycore.banktransfer.rail;

/**
 * The rail could not give an answer (outage, timeout). Details are
 * logged, not returned to the customer.
 */
public class BankRailException extends RuntimeException {

    public BankRailException(String message, Throwable cause) {
        super(message, cause);
    }
}
