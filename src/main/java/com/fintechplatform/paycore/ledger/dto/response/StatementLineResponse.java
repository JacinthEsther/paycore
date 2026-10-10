package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementLineResponse(
        Instant postedAt,
        UUID transactionId,
        String reference,
        LedgerTransactionType transactionType,
        LedgerTransactionStatus transactionStatus,
        String description,
        String counterpartyName,
        String counterpartyBank,
        LedgerEntryType direction,
        BigDecimal amount,
        BigDecimal balanceAfter
) {
}
