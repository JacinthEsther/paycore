package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycTierUpgradeStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KycTierUpgradeResponse(
        UUID id,
        UUID kycId,
        UUID customerId,
        String customerName,
        String customerEmail,
        int currentTier,
        int requestedTier,
        UUID documentId,
        KycDocumentType documentType,
        KycTierUpgradeStatus status,
        String reason,
        Instant requestedAt,
        Instant decidedAt,
        Instant reviewDueAt,
        List<String> flags,
        boolean decidedAutomatically
) {
}
