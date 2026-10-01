package com.fintechplatform.paycore.banktransfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A transfer from one of the caller's accounts (in the path) to an
 * account at another bank. The beneficiary's name is not sent: PayCore
 * looks it up with the bank (name enquiry) and records what the bank says.
 *
 * Retrying with the same idempotency key returns the original transfer
 * without paying again.
 */
public record OutboundTransferRequest(

        @NotBlank(message = "Bank code is required")
        String bankCode,

        @NotBlank(message = "Account number is required")
        @Pattern(regexp = "\\d{10}", message = "Account number must be 10 digits")
        String accountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
        String currency,

        @Size(max = 100, message = "Narration must not exceed 100 characters")
        String narration,

        @NotBlank(message = "Idempotency key is required")
        @Size(min = 8, max = 100, message = "Idempotency key must be between 8 and 100 characters")
        String idempotencyKey
) {
}
