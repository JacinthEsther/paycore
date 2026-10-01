package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One movement on the account: direction is CREDIT for money in and
 * DEBIT for money out, and balanceAfter is the running balance once it
 * was applied. transactionStatus shows REVERSED on a transaction that was
 * later undone; the reversal is its own line.
 *
 * counterpartyName and counterpartyBank name the other side: the other
 * customer for a transfer inside PayCore ("PayCore"), the other bank's
 * account holder for a transfer in or out, null for top-ups and
 * adjustments.
 */
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
