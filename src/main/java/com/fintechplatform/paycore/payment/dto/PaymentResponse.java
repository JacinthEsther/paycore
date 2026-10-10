package com.fintechplatform.paycore.payment.dto;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.enums.PaymentType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String reference,
        PaymentType type,
        PaymentStatus status,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String provider,
        String externalReference,
        String providerReference,
        String narration,
        Counterparty counterparty,
        UUID ledgerTransactionId,
        UUID reversalTransactionId,
        String failureReason,
        String refundReference,
        int statusChecks,
        Instant nextCheckAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Bill bill
) {

    public record Bill(String billerId, String productCode, String productName, String token) {
    }

    public static PaymentResponse from(Payment payment) {

        Counterparty counterparty =
                payment.getCounterpartyName() == null
                        ? null
                        : new Counterparty(
                                payment.getCounterpartyName(),
                                payment.getCounterpartyBank(),
                                payment.getCounterpartyAccountNumber()
                        );

        return new PaymentResponse(
                payment.getId(),
                payment.getReference(),
                payment.getType(),
                payment.getStatus(),
                payment.getAccountId(),
                BigDecimal.valueOf(payment.getAmountMinor(), Currency.of(payment.getCurrency()).minorUnit()),
                payment.getCurrency(),
                payment.getProvider(),
                payment.getExternalReference(),
                payment.getProviderReference(),
                payment.getNarration(),
                counterparty,
                payment.getLedgerTransactionId(),
                payment.getReversalTransactionId(),
                payment.getFailureReason(),
                payment.getRefundReference(),
                payment.getStatusChecks(),
                payment.getNextCheckAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getCompletedAt(),
                payment.isBillPayment()
                        ? new Bill(
                                payment.getBillBillerId(),
                                payment.getBillProductCode(),
                                payment.getBillProductName(),
                                payment.getBillToken()
                        )
                        : null
        );
    }
}
