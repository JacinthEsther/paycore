package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.UUID;

public record KycResponse(
        UUID id,
        UUID customerId,
        KycStatus status,
        String reviewReason,
        Instant createdAt,
        Instant updatedAt
) {
}
