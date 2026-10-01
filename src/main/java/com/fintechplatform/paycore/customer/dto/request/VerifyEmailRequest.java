package com.fintechplatform.paycore.customer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token from the link in a verification email.
 */
public record VerifyEmailRequest(

        @NotBlank(message = "Token is required")
        @Size(max = 100, message = "Token is too long")
        String token
) {
}
