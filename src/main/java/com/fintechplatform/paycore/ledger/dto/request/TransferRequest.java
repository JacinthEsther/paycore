package com.fintechplatform.paycore.ledger.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A transfer from one of the caller's accounts (in the path) to any
 * PayCore account, identified the way a sender knows it: by its 10-digit
 * account number.
 *
 * The amount is in major units as people write it (20000.50); the ledger
 * converts it to minor units exactly and rejects extra decimal places.
 *
 * Retrying with the same idempotency key and the same details returns the
 * original transaction instead of moving the money again.
 */
public record TransferRequest(

        @NotBlank(message = "Destination account number is required")
        @Pattern(regexp = "\\d{10}", message = "Destination account number must be 10 digits")
        String destinationAccountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
        String currency,

        @NotBlank(message = "Idempotency key is required")
        @Size(min = 8, max = 100, message = "Idempotency key must be between 8 and 100 characters")
        String idempotencyKey,

        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description
) {
}
