package com.fintechplatform.paycore.ledger.exception;

import com.fintechplatform.paycore.ledger.domain.Money;

public class FundingLimitExceededException extends RuntimeException {

    public FundingLimitExceededException(Money limit, Money remaining) {
        super(
                "Top-ups to this account are limited to "
                        + limit.toMajor().toPlainString() + " " + limit.currency().code()
                        + " in total; " + remaining.toMajor().toPlainString() + " "
                        + remaining.currency().code() + " can still be added"
        );
    }
}
