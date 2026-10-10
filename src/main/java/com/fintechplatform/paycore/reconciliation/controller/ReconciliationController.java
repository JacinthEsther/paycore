package com.fintechplatform.paycore.reconciliation.controller;

import com.fintechplatform.paycore.reconciliation.dto.ReconciliationItemResponse;
import com.fintechplatform.paycore.reconciliation.dto.ReconciliationRunDetailResponse;
import com.fintechplatform.paycore.reconciliation.dto.ReconciliationRunResponse;
import com.fintechplatform.paycore.reconciliation.dto.ResolveBreakRequest;
import com.fintechplatform.paycore.reconciliation.dto.RunReconciliationRequest;
import com.fintechplatform.paycore.reconciliation.entity.ReconciliationRun;
import com.fintechplatform.paycore.reconciliation.service.ReconciliationService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ops/reconciliation")
public class ReconciliationController {

    private static final int RECENT_RUNS = 30;

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('LEDGER_REQUEST')")
    public ResponseEntity<ReconciliationRunResponse> run(
            @AuthenticationPrincipal CurrentUser officer,
            @Valid @RequestBody RunReconciliationRequest request
    ) {

        ReconciliationRun run = reconciliationService.run(
                request.provider(),
                request.businessDate() == null ? reconciliationService.today() : request.businessDate(),
                officer.customerId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ReconciliationRunResponse.from(run));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public List<ReconciliationRunResponse> runs() {
        return reconciliationService.recentRuns(RECENT_RUNS).stream().map(ReconciliationRunResponse::from).toList();
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public ReconciliationRunDetailResponse run(@PathVariable UUID runId) {

        return new ReconciliationRunDetailResponse(
                ReconciliationRunResponse.from(reconciliationService.getRun(runId)),
                reconciliationService.items(runId).stream().map(ReconciliationItemResponse::from).toList()
        );
    }

    @PostMapping("/items/{itemId}/redeliver")
    @PreAuthorize("hasAuthority('LEDGER_REQUEST')")
    public ReconciliationItemResponse redeliver(
            @AuthenticationPrincipal CurrentUser officer,
            @PathVariable UUID itemId
    ) {
        return ReconciliationItemResponse.from(reconciliationService.redeliver(itemId, officer.customerId()));
    }

    @PostMapping("/items/{itemId}/resolve")
    @PreAuthorize("hasAuthority('LEDGER_APPROVE')")
    public ReconciliationItemResponse resolve(
            @AuthenticationPrincipal CurrentUser supervisor,
            @PathVariable UUID itemId,
            @Valid @RequestBody ResolveBreakRequest request
    ) {
        return ReconciliationItemResponse.from(
                reconciliationService.resolve(itemId, supervisor.customerId(), request.note())
        );
    }
}
