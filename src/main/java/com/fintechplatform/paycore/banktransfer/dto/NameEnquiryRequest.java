package com.fintechplatform.paycore.banktransfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record NameEnquiryRequest(

        @NotBlank(message = "Bank code is required")
        String bankCode,

        @NotBlank(message = "Account number is required")
        @Pattern(regexp = "\\d{10}", message = "Account number must be 10 digits")
        String accountNumber
) {
}
