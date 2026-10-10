package com.fintechplatform.paycore.funding.controller;

import com.fintechplatform.paycore.funding.service.FundingOutcome;
import com.fintechplatform.paycore.funding.service.FundingService;
import com.fintechplatform.paycore.payment.dto.PaymentResponse;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.dto.response.FundingAllowanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger/accounts/{accountId}")
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping("/fundings")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public ResponseEntity<?> fund(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId,
            @Valid @RequestBody FundAccountRequest request
    ) {

        FundingOutcome outcome = fundingService.fund(currentUser.customerId(), accountId, request);

        if (!outcome.isCredited()) {
            PaymentResponse payment = outcome.payment();

            return ResponseEntity
                    .accepted()
                    .location(URI.create("/api/v1/payments/" + payment.id()))
                    .body(payment);
        }

        TransactionResponse funding = outcome.transaction();

        return ResponseEntity
                .created(URI.create("/api/v1/ledger/transactions/" + funding.id()))
                .body(funding);
    }

    @GetMapping("/funding")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public FundingAllowanceResponse allowance(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId
    ) {
        return fundingService.allowance(currentUser.customerId(), accountId);
    }
}
