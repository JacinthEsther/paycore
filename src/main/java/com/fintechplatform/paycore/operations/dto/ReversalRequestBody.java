package com.fintechplatform.paycore.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReversalRequestBody(

        @NotNull(message = "Transaction is required")
        UUID transactionId,

        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason,

        @Size(max = 500, message = "Customer description must not exceed 500 characters")
        String customerDescription
) {
}
