package com.fintechplatform.paycore.banktransfer.exception;

import com.fintechplatform.paycore.ledger.domain.Money;

public class SimulatorLimitExceededException extends RuntimeException {

    public SimulatorLimitExceededException(Money limit, Money remaining) {
        super(
                "Test Bank sends at most " + limit.toMajor().toPlainString() + " " + limit.currency().code()
                        + " to one account; " + remaining.toMajor().toPlainString() + " "
                        + remaining.currency().code() + " can still be sent"
        );
    }
}
