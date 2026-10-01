package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * amount is the total debited, which equals the total credited.
 *
 * counterparty is the account at another bank for a transfer in or out;
 * provider and providerReference name the card processor or bank rail
 * that carried the money and its own reference for it (a NIP session id
 * for bank transfers), which is what customers quote to their bank.
 *
 * reversedAt is set once the transaction has been reversed; reversalOf is
 * set on a REVERSAL and names the transaction it undid.
 */
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
