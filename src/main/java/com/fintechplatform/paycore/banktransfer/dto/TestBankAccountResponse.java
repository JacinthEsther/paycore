package com.fintechplatform.paycore.banktransfer.dto;

import java.math.BigDecimal;

public record TestBankAccountResponse(
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName,
        String currency,
        BigDecimal limitPerAccount,
        BigDecimal remaining
) {
}
