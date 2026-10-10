package com.fintechplatform.paycore.funding.provider;

import com.fintechplatform.paycore.ledger.domain.Money;

import java.util.UUID;

public record FundingCollection(
        UUID customerId,
        UUID accountId,
        Money amount,
        String merchantReference
) {
}
