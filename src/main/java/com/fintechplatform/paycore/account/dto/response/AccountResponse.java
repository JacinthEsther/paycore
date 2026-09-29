package com.fintechplatform.paycore.account.dto.response;

import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID customerId,
        String accountNumber,
        AccountType type,
        AccountStatus status,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt
) {
}
