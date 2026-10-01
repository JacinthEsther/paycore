package com.fintechplatform.paycore.ledger.domain;

import java.util.Objects;

/**
 * The account at another bank on the other side of a bank transfer: the
 * sender of an inbound transfer, the beneficiary of an outbound one.
 */
public record Counterparty(
        String name,
        String bank,
        String accountNumber
) {
    public Counterparty {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Counterparty name is required");
        }
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(accountNumber, "accountNumber");
    }
}
