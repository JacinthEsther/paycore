package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.ledger.domain.Counterparty;

import java.math.BigDecimal;

public record InboundTransfer(
        String provider,
        String sessionId,
        String destinationAccountNumber,
        BigDecimal amount,
        String currency,
        Counterparty sender,
        String narration
) {
}
