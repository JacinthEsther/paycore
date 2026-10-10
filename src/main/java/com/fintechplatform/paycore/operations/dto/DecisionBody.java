package com.fintechplatform.paycore.operations.dto;

import jakarta.validation.constraints.Size;

public record DecisionBody(

        @Size(max = 500, message = "Note must not exceed 500 characters")
        String note
) {
}
