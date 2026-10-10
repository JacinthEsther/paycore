package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.account.enums.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

public record SystemAccountResponse(
        UUID accountId,
        AccountType type,
        String currency,
        BigDecimal balance
) {
}
