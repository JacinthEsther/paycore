package com.fintechplatform.paycore.funding.provider;

import java.time.Instant;

public record CardSettlementLine(
        String merchantReference,
        String providerReference,
        long amountMinor,
        String currency,
        String status,
        String refundReference,
        Instant createdAt
) {
}
