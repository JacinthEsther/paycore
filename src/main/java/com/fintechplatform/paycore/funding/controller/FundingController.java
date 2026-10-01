package com.fintechplatform.paycore.funding.controller;

import com.fintechplatform.paycore.funding.service.FundingService;
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

/**
 * Customers adding money to their own accounts through the payment
 * provider. Only ever the caller's own accounts (404 otherwise).
 */
@RestController
@RequestMapping("/api/v1/ledger/accounts/{accountId}")
public class FundingController {

    private final FundingService fundingService;

    public FundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    /**
     * 201 with the deposit once the provider confirmed the payment; 422
     * PAYMENT_DECLINED if it did not, with nothing credited. A retry with
     * the same idempotency key returns the same deposit and charges
     * nothing.
     */
    @PostMapping("/fundings")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public ResponseEntity<TransactionResponse> fund(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId,
            @Valid @RequestBody FundAccountRequest request
    ) {

        TransactionResponse funding = fundingService.fund(currentUser.customerId(), accountId, request);

        return ResponseEntity
                .created(URI.create("/api/v1/ledger/transactions/" + funding.id()))
                .body(funding);
    }

    /**
     * Whether top-ups are available, and how much more can be added.
     */
    @GetMapping("/funding")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public FundingAllowanceResponse allowance(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId
    ) {
        return fundingService.allowance(currentUser.customerId(), accountId);
    }
}
