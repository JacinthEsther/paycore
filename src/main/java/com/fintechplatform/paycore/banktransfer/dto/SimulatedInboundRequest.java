package com.fintechplatform.paycore.banktransfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The signed-in customer sending money from their Test Bank account to a
 * PayCore account (usually their own).
 */
public record SimulatedInboundRequest(

        @NotBlank(message = "Destination account number is required")
        @Pattern(regexp = "\\d{10}", message = "Destination account number must be 10 digits")
        String destinationAccountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @Size(max = 100, message = "Narration must not exceed 100 characters")
        String narration
) {
}
