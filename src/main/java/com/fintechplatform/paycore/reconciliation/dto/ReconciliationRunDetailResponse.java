package com.fintechplatform.paycore.reconciliation.dto;

import java.util.List;

public record ReconciliationRunDetailResponse(
        ReconciliationRunResponse run,
        List<ReconciliationItemResponse> items
) {
}
