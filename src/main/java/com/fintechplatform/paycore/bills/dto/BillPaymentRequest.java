package com.fintechplatform.paycore.bills.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record BillPaymentRequest(

        @NotBlank(message = "Biller is required")
        String billerId,

        @Size(max = 100, message = "Product code must not exceed 100 characters")
        String productCode,

        @NotBlank(message = "Customer number is required")
        @Size(max = 20, message = "Customer number must not exceed 20 characters")
        String customerNumber,

        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        BigDecimal amount,

        @NotBlank(message = "Idempotency key is required")
        @Size(min = 8, max = 100, message = "Idempotency key must be between 8 and 100 characters")
        String idempotencyKey
) {
}
