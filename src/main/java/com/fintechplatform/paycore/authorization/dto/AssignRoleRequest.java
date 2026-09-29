package com.fintechplatform.paycore.authorization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignRoleRequest(

        @NotBlank(message = "Role is required")
        @Size(max = 50, message = "Role must not exceed 50 characters")
        String role,

        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {
}
