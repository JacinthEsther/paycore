package com.fintechplatform.paycore.ledger.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much more the customer can add to the account through a payment
 * provider. funded counts provider deposits that still stand (reversed
 * ones do not); staff deposits and incoming transfers never count.
 *
 * enabled is false, and provider null, when no funding provider is
 * configured; the amounts are then still reported.
 */
public record FundingAllowanceResponse(
        UUID accountId,
        boolean enabled,
        String provider,
        String currency,
        BigDecimal limit,
        BigDecimal funded,
        BigDecimal remaining
) {
}
