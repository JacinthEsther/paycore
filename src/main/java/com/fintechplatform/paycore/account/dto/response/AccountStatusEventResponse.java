package com.fintechplatform.paycore.account.dto.response;

import com.fintechplatform.paycore.account.enums.AccountEventType;
import com.fintechplatform.paycore.account.enums.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountStatusEventResponse(
        UUID id,
        AccountEventType eventType,
        AccountStatus fromStatus,
        AccountStatus toStatus,
        UUID performedBy,
        String reason,
        Instant occurredAt
) {
}
