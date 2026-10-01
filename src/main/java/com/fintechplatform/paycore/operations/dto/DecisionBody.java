package com.fintechplatform.paycore.operations.dto;

import jakarta.validation.constraints.Size;

/** The checker's note: optional when approving, required when rejecting. */
public record DecisionBody(

        @Size(max = 500, message = "Note must not exceed 500 characters")
        String note
) {
}
