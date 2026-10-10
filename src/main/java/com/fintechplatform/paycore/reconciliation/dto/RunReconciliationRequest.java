package com.fintechplatform.paycore.reconciliation.dto;

import com.fintechplatform.paycore.reconciliation.enums.ProviderKind;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record RunReconciliationRequest(

        @NotNull(message = "Provider is required (BANK_RAIL or CARD)")
        ProviderKind provider,

        LocalDate businessDate
) {
}
