package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded BVN check. The BVN itself was never stored, so it cannot
 * appear here. {@code countsTowardLimit} is true for FAILED checks
 * inside the current window (and after any reset). {@code ipAddress} is
 * the normalized client network (IPv6 as its /64); null for attempts
 * recorded before networks were tracked.
 */
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
