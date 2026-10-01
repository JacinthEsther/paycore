package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * accountName is the account holder as a receipt names them ("PayCore"
 * for PayCore's own settlement account, which has no account number).
 */
public record LedgerEntryResponse(
        UUID id,
        UUID accountId,
        String accountNumber,
        String accountName,
        LedgerEntryType type,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
