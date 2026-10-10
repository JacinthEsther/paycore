package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.ledger.domain.Counterparty;

import java.math.BigDecimal;

public record OutboundTransfer(
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String narration,
        String provider,
        String sessionId,
        Counterparty beneficiary
) {
}
