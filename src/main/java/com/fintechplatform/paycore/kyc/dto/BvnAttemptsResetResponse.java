package com.fintechplatform.paycore.kyc.dto;

import java.time.Instant;
import java.util.UUID;

public record BvnAttemptsResetResponse(
        UUID kycId,
        int remainingAttempts,
        Instant resetAt,
        UUID resetBy
) {
}
