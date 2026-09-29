package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately omits the document number.
 */
public record KycDocumentResponse(
        UUID id,
        UUID kycId,
        KycDocumentType documentType,
        KycStatus kycStatus,
        Instant createdAt
) {
}
