package com.fintechplatform.paycore.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveBreakRequest(

        @NotBlank(message = "A note is required")
        @Size(max = 500, message = "Note must not exceed 500 characters")
        String note
) {
}
