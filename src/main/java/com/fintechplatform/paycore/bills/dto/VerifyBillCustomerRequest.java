package com.fintechplatform.paycore.bills.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyBillCustomerRequest(

        @NotBlank(message = "Biller is required")
        String billerId,

        @Size(max = 100, message = "Product code must not exceed 100 characters")
        String productCode,

        @NotBlank(message = "Customer number is required")
        @Size(max = 20, message = "Customer number must not exceed 20 characters")
        String customerNumber
) {
}
