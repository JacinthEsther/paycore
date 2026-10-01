package com.fintechplatform.paycore.ledger.dto.response;

import com.fintechplatform.paycore.kyc.dto.PageInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An account statement for the calendar days from..to (inclusive) in
 * timeZone. openingBalance + totalCredits - totalDebits = closingBalance
 * always holds, whichever page of lines this is.
 */
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
