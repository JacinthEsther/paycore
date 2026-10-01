package com.fintechplatform.paycore.banktransfer.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * What the bank rail tells PayCore when another bank sends money to a
 * PayCore account. sessionId identifies the payment on the rail; the same
 * notification may be delivered more than once.
 */
public record InboundTransferNotification(

        @NotBlank(message = "Session id is required")
        @Size(max = 100, message = "Session id is too long")
        String sessionId,

        @NotBlank(message = "Destination account number is required")
        @Pattern(regexp = "\\d{10}", message = "Destination account number must be 10 digits")
        String destinationAccountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
        String currency,

        @NotBlank(message = "Sender name is required")
        @Size(max = 200, message = "Sender name is too long")
        String senderName,

        @NotBlank(message = "Sender bank is required")
        @Size(max = 100, message = "Sender bank is too long")
        String senderBank,

        @NotBlank(message = "Sender account number is required")
        @Size(max = 20, message = "Sender account number is too long")
        String senderAccountNumber,

        @Size(max = 100, message = "Narration must not exceed 100 characters")
        String narration
) {
}
