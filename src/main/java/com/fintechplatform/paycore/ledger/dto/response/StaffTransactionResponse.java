package com.fintechplatform.paycore.ledger.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.UUID;

/**
 * A transaction as staff see it: everything the customer sees, flattened
 * into the same JSON object, plus the internal staff note, who initiated
 * it (null for an inbound transfer) and, for a staff correction, the
 * officer who approved it.
 *
 * A separate type on purpose. {@link TransactionResponse}, which customer
 * endpoints return, has no field that could carry a staff note, so a
 * mapping mistake cannot leak one.
 */
public record StaffTransactionResponse(
        @JsonUnwrapped
        TransactionResponse transaction,
        String staffNote,
        UUID initiatedBy,
        UUID approvedBy
) {
}
