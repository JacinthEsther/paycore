package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;

import java.time.Instant;
import java.util.UUID;

public record BvnAttemptResponse(
        UUID id,
        VerificationResult result,
        String provider,
        String providerReference,
        String reason,
        String ipAddress,
        Instant createdAt,
        boolean countsTowardLimit
) {
}
