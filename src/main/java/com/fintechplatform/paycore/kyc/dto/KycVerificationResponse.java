package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;

import java.util.UUID;

/**
 * Never echoes the identity data the provider returned.
 * {@code remainingAttempts} is how many more FAILED BVN checks are
 * allowed in the current window before checks are blocked.
 */
public record KycVerificationResponse(
        UUID kycId,
        VerificationResult result,
        KycStatus status,
        String provider,
        String reason,
        int remainingAttempts
) {
}
