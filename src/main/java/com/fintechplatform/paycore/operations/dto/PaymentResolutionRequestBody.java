package com.fintechplatform.paycore.operations.dto;

import com.fintechplatform.paycore.payment.enums.PaymentResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PaymentResolutionRequestBody(

        @NotNull(message = "Payment is required")
        UUID paymentId,

        @NotNull(message = "Resolution is required (COMPLETE, REVERSE, CREDIT, REFUND or CLOSE)")
        PaymentResolution resolution,

        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason,

        @Size(max = 500, message = "Customer description must not exceed 500 characters")
        String customerDescription
) {
}
