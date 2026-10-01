package com.fintechplatform.paycore.ledger.controller;

import com.fintechplatform.paycore.ledger.dto.response.BalanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.StatementResponse;
import com.fintechplatform.paycore.ledger.dto.response.SystemAccountResponse;
import com.fintechplatform.paycore.ledger.dto.response.StaffTransactionResponse;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.StatementService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Staff reads of the ledger: any transaction (with its staff note), any
 * customer account's balance and statement, and PayCore's own system
 * accounts for reconciliation.
 *
 * Staff cannot move money here. Money enters and leaves through payment
 * providers and bank transfers; corrections go through maker-checker
 * requests (OperationsController).
 */
@RestController
@RequestMapping("/api/v1/admin/ledger")
public class AdminLedgerController {

    private final LedgerService ledgerService;
    private final StatementService statementService;

    public AdminLedgerController(LedgerService ledgerService, StatementService statementService) {
        this.ledgerService = ledgerService;
        this.statementService = statementService;
    }

    @GetMapping("/transactions/{transactionId}")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public StaffTransactionResponse getTransaction(@PathVariable UUID transactionId) {

        return ledgerService.getAnyTransaction(transactionId);
    }

    @GetMapping("/transactions/reference/{reference}")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public StaffTransactionResponse getTransactionByReference(@PathVariable String reference) {

        return ledgerService.getAnyTransactionByReference(reference);
    }

    @GetMapping("/accounts/{accountId}/balance")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public BalanceResponse getBalance(@PathVariable UUID accountId) {

        return ledgerService.getCustomerAccountBalance(accountId);
    }

    /**
     * Any customer account's statement; same shape and defaults as the
     * customer's own.
     */
    @GetMapping("/accounts/{accountId}/statement")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public StatementResponse getStatement(
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

        return statementService.getCustomerAccountStatement(accountId, from, to, page, size);
    }

    @GetMapping("/system-accounts")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public List<SystemAccountResponse> getSystemAccounts() {

        return ledgerService.getSystemAccounts();
    }
}
