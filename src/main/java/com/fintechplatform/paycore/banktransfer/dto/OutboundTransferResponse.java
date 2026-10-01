package com.fintechplatform.paycore.banktransfer.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;

/**
 * The debit for a transfer to another bank, flattened into the same JSON
 * object, plus what happened on the rail:
 *
 * <pre>
 * SUCCESSFUL  the beneficiary bank accepted the payment
 * FAILED      it was rejected; the debit was reversed (reversalReference)
 * PENDING     the outcome is not known yet; the debit stands until it is
 * </pre>
 */
public record OutboundTransferResponse(
        @JsonUnwrapped
        TransactionResponse transaction,
        String transferStatus,
        String failureReason,
        String reversalReference
) {
    public static final String SUCCESSFUL = "SUCCESSFUL";
    public static final String FAILED = "FAILED";
    public static final String PENDING = "PENDING";
}
