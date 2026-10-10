package com.fintechplatform.paycore.ledger.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.UUID;

public record StaffTransactionResponse(
        @JsonUnwrapped
        TransactionResponse transaction,
        String staffNote,
        UUID initiatedBy,
        UUID approvedBy
) {
}
