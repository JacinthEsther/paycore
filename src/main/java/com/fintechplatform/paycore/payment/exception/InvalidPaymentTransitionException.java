package com.fintechplatform.paycore.payment.exception;

import com.fintechplatform.paycore.payment.enums.PaymentStatus;

public class InvalidPaymentTransitionException extends RuntimeException {

    public InvalidPaymentTransitionException(String reference, PaymentStatus from, PaymentStatus to) {
        super("Payment " + reference + " cannot move from " + from + " to " + to);
    }
}
