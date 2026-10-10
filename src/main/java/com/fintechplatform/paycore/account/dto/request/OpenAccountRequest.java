package com.fintechplatform.paycore.account.dto.request;

import com.fintechplatform.paycore.account.enums.AccountType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OpenAccountRequest(

        @NotNull(message = "Account type is required")
        AccountType type,

        @NotNull(message = "Currency is required")
        @Pattern(
                regexp = "\\s*[A-Za-z]{3}\\s*",
                message = "Currency must be a 3-letter ISO 4217 code, e.g. NGN"
        )
        String currency
) {
}
