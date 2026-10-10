package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycDocumentType;

import java.time.Instant;
import java.util.UUID;

public record KycDocumentSummary(UUID id, KycDocumentType documentType, String documentNumber, Instant uploadedAt) {
}
