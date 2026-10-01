package com.fintechplatform.paycore.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A request to undo a posted transaction. reason is for staff only;
 * customerDescription is what the customers involved read (default
 * "Reversal of TXN-...").
 */
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
