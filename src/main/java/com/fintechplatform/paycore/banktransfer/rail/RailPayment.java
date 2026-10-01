package com.fintechplatform.paycore.banktransfer.rail;

import com.fintechplatform.paycore.ledger.domain.Money;

/**
 * A payment to another bank. sessionId is PayCore's unique id for it on
 * the rail; sending the same session twice must not pay twice.
 */
public record RailPayment(
        String sessionId,
        String bankCode,
        String accountNumber,
        String accountName,
        Money amount,
        String narration,
        String senderName
) {
}
