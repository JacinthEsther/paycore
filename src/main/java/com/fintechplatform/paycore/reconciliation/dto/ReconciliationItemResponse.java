package com.fintechplatform.paycore.reconciliation.dto;

import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.reconciliation.entity.ReconciliationItem;
import com.fintechplatform.paycore.reconciliation.enums.ItemKind;
import com.fintechplatform.paycore.reconciliation.enums.ReconciliationResult;
import com.fintechplatform.paycore.reconciliation.enums.ResolutionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationItemResponse(
        UUID id,
        ItemKind kind,
        String externalReference,
        ReconciliationResult result,
        String currency,
        BigDecimal providerAmount,
        String providerStatus,
        BigDecimal paycoreAmount,
        String paycoreStatus,
        UUID paymentId,
        UUID ledgerTransactionId,
        UUID accountId,
        String detail,
        ResolutionStatus resolutionStatus,
        UUID resolvedBy,
        Instant resolvedAt,
        String resolutionNote
) {

    public static ReconciliationItemResponse from(ReconciliationItem item) {

        int minorUnit = Currency.of(item.getCurrency()).minorUnit();

        return new ReconciliationItemResponse(
                item.getId(),
                item.getKind(),
                item.getExternalReference(),
                item.getResult(),
                item.getCurrency(),
                item.getProviderAmountMinor() == null ? null : BigDecimal.valueOf(item.getProviderAmountMinor(), minorUnit),
                item.getProviderStatus(),
                item.getPaycoreAmountMinor() == null ? null : BigDecimal.valueOf(item.getPaycoreAmountMinor(), minorUnit),
                item.getPaycoreStatus(),
                item.getPaymentId(),
                item.getLedgerTransactionId(),
                item.getAccountId(),
                item.getDetail(),
                item.getResolutionStatus(),
                item.getResolvedBy(),
                item.getResolvedAt(),
                item.getResolutionNote()
        );
    }
}
