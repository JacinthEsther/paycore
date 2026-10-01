package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.account.enums.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One of PayCore's own accounts. For SETTLEMENT the balance is the money
 * PayCore holds for customers in that currency: deposits minus
 * withdrawals. It should always equal the sum of customer balances in the
 * same currency, which is what reconciliation checks against the bank.
 */
public record SystemAccountResponse(
        UUID accountId,
        AccountType type,
        String currency,
        BigDecimal balance
) {
}
