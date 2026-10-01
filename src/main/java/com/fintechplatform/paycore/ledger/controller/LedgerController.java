package com.fintechplatform.paycore.ledger.controller;

import com.fintechplatform.paycore.ledger.dto.request.TransferRequest;
import com.fintechplatform.paycore.ledger.dto.response.BalanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.StatementResponse;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.StatementService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Customer money movement and balances. The customer id always comes from
 * the access token: transfers only leave the caller's own accounts, and
 * balances and transactions are only visible to the customers involved.
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final StatementService statementService;

    public LedgerController(LedgerService ledgerService, StatementService statementService) {
        this.ledgerService = ledgerService;
        this.statementService = statementService;
    }

    /**
     * 201 with the transaction. A retry with the same idempotency key
     * returns the same transaction again.
     */
    @PostMapping("/accounts/{sourceAccountId}/transfers")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID sourceAccountId,
            @Valid @RequestBody TransferRequest request
    ) {

        TransactionResponse transaction =
                ledgerService.transfer(currentUser.customerId(), sourceAccountId, request);

        return ResponseEntity
                .created(URI.create("/api/v1/ledger/transactions/" + transaction.id()))
                .body(transaction);
    }

    @GetMapping("/accounts/{accountId}/balance")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public BalanceResponse getBalance(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId
    ) {

        return ledgerService.getBalance(currentUser.customerId(), accountId);
    }

    /**
     * Opening and closing balance, totals and the lines with running
     * balances for whole days (ISO dates, e.g. 2026-09-01) in PayCore's
     * statement time zone. Defaults to the month to date.
     */
    @GetMapping("/accounts/{accountId}/statement")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public StatementResponse getStatement(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater")
            int page,

            @RequestParam(defaultValue = "50")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 200, message = "size must not exceed 200")
            int size
    ) {

        return statementService.getStatement(currentUser.customerId(), accountId, from, to, page, size);
    }

    @GetMapping("/transactions/{transactionId}")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public TransactionResponse getTransaction(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID transactionId
    ) {

        return ledgerService.getTransaction(currentUser.customerId(), transactionId);
    }

    @GetMapping("/transactions/reference/{reference}")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public TransactionResponse getTransactionByReference(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable String reference
    ) {

        return ledgerService.getTransactionByReference(currentUser.customerId(), reference);
    }
}
