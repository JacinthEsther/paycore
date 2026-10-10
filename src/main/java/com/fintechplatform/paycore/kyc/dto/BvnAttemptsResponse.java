package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BvnAttemptsResponse(
        UUID kycId,
        KycStatus kycStatus,
        int maxFailedAttempts,
        String attemptWindow,
        Instant countingSince,
        int failedAttemptsCounted,
        int remainingAttempts,
        boolean limited,
        Instant retryAfter,
        Instant resetAt,
        UUID resetBy,
        List<BvnAttemptResponse> attempts,
        PageInfo page
) {
}
