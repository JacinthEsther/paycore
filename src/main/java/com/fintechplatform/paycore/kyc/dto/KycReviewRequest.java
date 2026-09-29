package com.fintechplatform.paycore.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reviewer's explanation for a rejection or an information request.
 * The customer sees it.
 */
public record KycReviewRequest(

        @NotBlank(message = "Reason is required")
        @Size(max = 1000, message = "Reason must not exceed 1000 characters")
        String reason
) {
}
