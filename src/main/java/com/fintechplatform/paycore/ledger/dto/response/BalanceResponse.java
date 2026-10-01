package com.fintechplatform.paycore.ledger.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Derived from the ledger at the time of the request: credits minus
 * debits across all of the account's entries.
 */
public record BalanceResponse(
        UUID accountId,
        String accountNumber,
        BigDecimal balance,
        String currency
) {
}
