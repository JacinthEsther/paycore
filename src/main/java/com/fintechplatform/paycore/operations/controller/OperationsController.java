package com.fintechplatform.paycore.operations.controller;

import com.fintechplatform.paycore.operations.dto.AdjustmentRequestBody;
import com.fintechplatform.paycore.operations.dto.DecisionBody;
import com.fintechplatform.paycore.operations.dto.OperationsRequestResponse;
import com.fintechplatform.paycore.operations.dto.ReversalRequestBody;
import com.fintechplatform.paycore.operations.service.OperationsService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The operations desk: makers file corrections, checkers decide them.
 * Filing needs LEDGER_REQUEST, deciding LEDGER_APPROVE, and the same
 * officer can never decide their own request (403 FOUR_EYES_REQUIRED).
 */
@RestController
@RequestMapping("/api/v1/ops/requests")
public class OperationsController {

    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('LEDGER_REQUEST')")
    public ResponseEntity<OperationsRequestResponse> requestAdjustment(
            @AuthenticationPrincipal CurrentUser officer,
            @Valid @RequestBody AdjustmentRequestBody body
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(operationsService.requestAdjustment(officer.customerId(), body));
    }

    @PostMapping("/reversals")
    @PreAuthorize("hasAuthority('LEDGER_REQUEST')")
    public ResponseEntity<OperationsRequestResponse> requestReversal(
            @AuthenticationPrincipal CurrentUser officer,
            @Valid @RequestBody ReversalRequestBody body
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(operationsService.requestReversal(officer.customerId(), body));
    }

    /** status=PENDING (default) for the queue, DECIDED for recent decisions. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('LEDGER_REQUEST', 'LEDGER_APPROVE')")
    public List<OperationsRequestResponse> list(@RequestParam(defaultValue = "PENDING") String status) {

        if (!status.equals("PENDING") && !status.equals("DECIDED")) {
            throw new IllegalArgumentException("status must be PENDING or DECIDED");
        }

        return operationsService.list(status.equals("PENDING"));
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAnyAuthority('LEDGER_REQUEST', 'LEDGER_APPROVE')")
    public OperationsRequestResponse get(@PathVariable UUID requestId) {
        return operationsService.get(requestId);
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAuthority('LEDGER_APPROVE')")
    public OperationsRequestResponse approve(
            @AuthenticationPrincipal CurrentUser officer,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) DecisionBody body
    ) {
        return operationsService.approve(officer.customerId(), requestId, body == null ? null : body.note());
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAuthority('LEDGER_APPROVE')")
    public OperationsRequestResponse reject(
            @AuthenticationPrincipal CurrentUser officer,
            @PathVariable UUID requestId,
            @Valid @RequestBody DecisionBody body
    ) {
        return operationsService.reject(officer.customerId(), requestId, body.note());
    }
}
