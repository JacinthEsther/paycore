package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.kyc.dto.PageInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StatementResponse(
        UUID accountId,
        String accountNumber,
        String currency,
        LocalDate from,
        LocalDate to,
        String timeZone,
        BigDecimal openingBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        BigDecimal closingBalance,
        List<StatementLineResponse> lines,
        PageInfo page
) {
}
