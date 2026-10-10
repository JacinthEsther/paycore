package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.ledger.domain.Counterparty;

import java.math.BigDecimal;

public record BillDebit(
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String description,
        String provider,
        String requestId,
        Counterparty biller
) {
}
