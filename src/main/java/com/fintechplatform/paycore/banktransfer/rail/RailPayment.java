package com.fintechplatform.paycore.banktransfer.rail;

import com.fintechplatform.paycore.ledger.domain.Money;

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
