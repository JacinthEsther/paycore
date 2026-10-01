package com.fintechplatform.paycore.operations.dto;

import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;
import com.fintechplatform.paycore.operations.enums.OperationsRequestType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A maker-checker request as the operations queue shows it. For an
 * ADJUSTMENT, account and direction say what changes; for a REVERSAL,
 * transaction names what is undone. amount and currency are the money
 * either way. resultReference is the ledger transaction an approval
 * posted.
 */
public record OperationsRequestResponse(
        UUID id,
        OperationsRequestType type,
        OperationsRequestStatus status,
        UUID accountId,
        String accountNumber,
        String accountName,
        LedgerEntryType direction,
        UUID transactionId,
        String transactionReference,
        String transactionDescription,
        BigDecimal amount,
        String currency,
        String reason,
        String customerDescription,
        UUID requestedBy,
        String requestedByName,
        Instant requestedAt,
        UUID decidedBy,
        String decidedByName,
        Instant decidedAt,
        String decisionNote,
        UUID resultTransactionId,
        String resultReference
) {
}
