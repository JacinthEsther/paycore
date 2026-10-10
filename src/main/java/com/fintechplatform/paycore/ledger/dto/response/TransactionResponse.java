package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String reference,
        LedgerTransactionType type,
        LedgerTransactionStatus status,
        BigDecimal amount,
        String currency,
        String description,
        Counterparty counterparty,
        String provider,
        String providerReference,
        Instant createdAt,
        Instant postedAt,
        Instant reversedAt,
        UUID reversalOf,
        List<LedgerEntryResponse> entries
) {
}
