package com.fintechplatform.paycore.ledger.service;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What the ledger needs to know about the funding provider in use: its
 * name (stored on every deposit it confirms), the narration customers see
 * on their statement, and the most provider deposits may add to one
 * account in total, in major units of the account's currency.
 */
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
