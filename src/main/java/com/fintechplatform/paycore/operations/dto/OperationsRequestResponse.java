package com.fintechplatform.paycore.operations.dto;

import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;
import com.fintechplatform.paycore.operations.enums.OperationsRequestType;
import com.fintechplatform.paycore.payment.enums.PaymentResolution;
import com.fintechplatform.paycore.payment.enums.PaymentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OperationsRequestResponse(
        UUID id,
        OperationsRequestType type,
        OperationsRequestStatus status,
        UUID accountId,
        String accountNumber,
        String accountName,
        LedgerEntryType direction,
        UUID transactionId,
        String transactionReference,
        String transactionDescription,
        BigDecimal amount,
        String currency,
        String reason,
        String customerDescription,
        UUID requestedBy,
        String requestedByName,
        Instant requestedAt,
        UUID decidedBy,
        String decidedByName,
        Instant decidedAt,
        String decisionNote,
        UUID resultTransactionId,
        String resultReference,
        UUID paymentId,
        String paymentReference,
        PaymentType paymentType,
        PaymentResolution resolution
) {
}
