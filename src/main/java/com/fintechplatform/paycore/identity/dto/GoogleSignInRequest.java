package com.fintechplatform.paycore.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param credential the ID token Google Identity Services handed the browser
 */
public record GoogleSignInRequest(

        @NotBlank(message = "Google credential is required")
        @Size(max = 4096, message = "Google credential is too long")
        String credential
) {
}
