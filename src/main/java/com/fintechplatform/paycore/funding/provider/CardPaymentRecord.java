package com.fintechplatform.paycore.funding.provider;

import java.time.Instant;

public record CardPaymentRecord(
        String merchantReference,
        String providerReference,
        long amountMinor,
        String currency,
        String status,
        String reason,
        String refundReference,
        Instant createdAt,
        Instant refundedAt,
        boolean settled
) {
    public static final String CONFIRMED = "CONFIRMED";
    public static final String DECLINED = "DECLINED";
    public static final String REFUNDED = "REFUNDED";
}
