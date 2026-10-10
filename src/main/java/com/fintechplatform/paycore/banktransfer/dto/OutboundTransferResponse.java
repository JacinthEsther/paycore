package com.fintechplatform.paycore.banktransfer.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;

import java.util.UUID;

public record OutboundTransferResponse(
        @JsonUnwrapped
        TransactionResponse transaction,
        String transferStatus,
        String failureReason,
        String reversalReference,
        UUID paymentId,
        String paymentReference,
        PaymentStatus paymentStatus
) {
    public static final String SUCCESSFUL = "SUCCESSFUL";
    public static final String FAILED = "FAILED";
    public static final String PENDING = "PENDING";
}
