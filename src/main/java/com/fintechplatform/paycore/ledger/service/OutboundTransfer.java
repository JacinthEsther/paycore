package com.fintechplatform.paycore.ledger.service;

import com.fintechplatform.paycore.ledger.domain.Counterparty;

import java.math.BigDecimal;

/**
 * A customer's transfer to another bank, ready to post: the beneficiary
 * as name enquiry confirmed it, and the session id the rail will be asked
 * to pay under.
 */
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
