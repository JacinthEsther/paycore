package com.fintechplatform.paycore.banktransfer.dto;

import java.math.BigDecimal;

/**
 * The signed-in customer's own (pretend) account at Test Bank, and how
 * much more it may still send to the given PayCore account: the simulator
 * caps what it sends to any one account, since its money is not real.
 */
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
