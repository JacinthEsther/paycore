package com.fintechplatform.paycore.reconciliation.dto;

import com.fintechplatform.paycore.reconciliation.entity.ReconciliationRun;
import com.fintechplatform.paycore.reconciliation.enums.ProviderKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationRunResponse(
        UUID id,
        ProviderKind providerKind,
        String provider,
        LocalDate businessDate,
        Instant windowStart,
        Instant windowEnd,
        boolean scheduled,
        int providerLines,
        int paycoreRecords,
        int matched,
        int breaks,
        BigDecimal providerTotal,
        BigDecimal paycoreTotal,
        Instant createdAt
) {

    public static ReconciliationRunResponse from(ReconciliationRun run) {
        return new ReconciliationRunResponse(
                run.getId(),
                run.getProviderKind(),
                run.getProvider(),
                run.getBusinessDate(),
                run.getWindowStart(),
                run.getWindowEnd(),
                run.getStartedBy() == null,
                run.getProviderLines(),
                run.getPaycoreRecords(),
                run.getMatched(),
                run.getBreaks(),
                BigDecimal.valueOf(run.getProviderTotalMinor(), 2),
                BigDecimal.valueOf(run.getPaycoreTotalMinor(), 2),
                run.getCreatedAt()
        );
    }
}
