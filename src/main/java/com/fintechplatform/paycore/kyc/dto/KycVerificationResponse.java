package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;

import java.util.UUID;

public record KycVerificationResponse(
        UUID kycId,
        VerificationResult result,
        KycStatus status,
        String provider,
        String reason,
        int remainingAttempts
) {
}
