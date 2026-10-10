package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KycReviewItemResponse(
        UUID kycId,
        UUID customerId,
        String customerName,
        String customerEmail,
        KycStatus status,
        String reviewReason,
        boolean bvnPassed,
        boolean ninPassed,
        List<KycDocumentType> documentTypes,
        Instant createdAt,
        Instant updatedAt
) {
}
