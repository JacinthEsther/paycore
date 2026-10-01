package com.fintechplatform.paycore.operations.dto;

import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A request to correct a customer account by hand: CREDIT gives the
 * customer money, DEBIT takes it, against PayCore's settlement account.
 * reason is for staff only; customerDescription is what the customer
 * reads (default "Account adjustment").
 */
public record AdjustmentRequestBody(

        @NotNull(message = "Account is required")
        UUID accountId,

        @NotNull(message = "Direction is required (CREDIT or DEBIT)")
        LedgerEntryType direction,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
        String currency,

        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason,

        @Size(max = 500, message = "Customer description must not exceed 500 characters")
        String customerDescription
) {
}
