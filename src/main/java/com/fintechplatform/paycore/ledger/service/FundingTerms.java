package com.fintechplatform.paycore.ledger.service;

import java.math.BigDecimal;
import java.util.Objects;

public record FundingTerms(
        String provider,
        String description,
        BigDecimal limitPerAccount
) {
    public FundingTerms {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(limitPerAccount, "limitPerAccount");
    }
}
