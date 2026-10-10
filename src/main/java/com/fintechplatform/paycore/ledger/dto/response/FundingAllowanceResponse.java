package com.fintechplatform.paycore.ledger.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

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
