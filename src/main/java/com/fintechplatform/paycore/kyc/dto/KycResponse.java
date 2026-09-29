package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code reviewReason} is the latest reviewer's explanation for a
 * rejection or an information request; null otherwise.
 */
public record KycResponse(
        UUID id,
        UUID customerId,
        KycStatus status,
        String reviewReason,
        Instant createdAt,
        Instant updatedAt
) {
}
