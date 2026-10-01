package com.fintechplatform.paycore.funding.provider;

import com.fintechplatform.paycore.ledger.domain.Money;

import java.util.UUID;

/**
 * A payment to take from the customer. merchantReference is PayCore's own
 * reference for it, derived from the customer's idempotency key, so a
 * provider that deduplicates by reference never charges a retry twice.
 */
public record FundingCollection(
        UUID customerId,
        UUID accountId,
        Money amount,
        String merchantReference
) {
}
