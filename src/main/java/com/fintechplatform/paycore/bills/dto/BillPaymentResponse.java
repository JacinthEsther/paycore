package com.fintechplatform.paycore.bills.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;

import java.util.UUID;

public record BillPaymentResponse(
        @JsonUnwrapped
        TransactionResponse transaction,
        String billStatus,
        String billerId,
        String productName,
        String customerNumber,
        String token,
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
