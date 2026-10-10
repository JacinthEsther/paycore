package com.fintechplatform.paycore.ledger.domain;

import java.util.Objects;

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
