package com.fintechplatform.paycore.funding.service;

import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.payment.dto.PaymentResponse;

public record FundingOutcome(TransactionResponse transaction, PaymentResponse payment) {

    public static FundingOutcome credited(TransactionResponse transaction) {
        return new FundingOutcome(transaction, null);
    }

    public static FundingOutcome pending(PaymentResponse payment) {
        return new FundingOutcome(null, payment);
    }

    public boolean isCredited() {
        return transaction != null;
    }
}
