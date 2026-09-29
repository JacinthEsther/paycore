package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One profile in the reviewer's queue: enough to decide what to open,
 * without document numbers or any provider identity data.
 *
 * @param bvnPassed     whether any BVN check on the profile PASSED
 * @param ninPassed     whether any NIN check on the profile PASSED
 * @param documentTypes types of the uploaded documents, oldest first
 */
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
