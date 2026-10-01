package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.ledger.domain.Counterparty;

import java.math.BigDecimal;

/**
 * A transfer another bank sent to a PayCore account, as the bank rail
 * reported it. sessionId is the rail's unique id for the payment.
 */
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
