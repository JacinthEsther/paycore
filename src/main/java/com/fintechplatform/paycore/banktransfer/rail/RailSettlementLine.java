package com.fintechplatform.paycore.banktransfer.rail;

import java.time.Instant;

public record RailSettlementLine(
        String sessionId,
        String direction,
        String bankCode,
        String accountNumber,
        long amountMinor,
        String currency,
        String status,
        String reason,
        Instant createdAt
) {
    public static final String OUTBOUND = "OUTBOUND";
    public static final String INBOUND = "INBOUND";
    public static final String SUCCESSFUL = "SUCCESSFUL";
}
