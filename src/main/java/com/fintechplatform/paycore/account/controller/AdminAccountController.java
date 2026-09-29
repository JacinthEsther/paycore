package com.fintechplatform.paycore.account.controller;

import com.fintechplatform.paycore.account.dto.request.AccountStatusChangeRequest;
import com.fintechplatform.paycore.account.dto.response.AccountResponse;
import com.fintechplatform.paycore.account.dto.response.AccountStatusEventResponse;
import com.fintechplatform.paycore.account.service.AccountService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Staff account administration. Support and admins can read any customer's
 * accounts and history; only admins can activate, freeze, unfreeze or
 * close, each with a reason that goes into the audit history.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/customers/{customerId}/accounts")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public List<AccountResponse> getCustomerAccounts(
            @PathVariable UUID customerId
    ) {

        return accountService.getCustomerAccounts(customerId);
    }

    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public AccountResponse getAccount(@PathVariable UUID accountId) {

        return accountService.getAccount(accountId);
    }

    @GetMapping("/accounts/{accountId}/history")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public List<AccountStatusEventResponse> getHistory(
            @PathVariable UUID accountId
    ) {

        return accountService.getHistory(accountId);
    }

    /**
     * PENDING -> ACTIVE. Refused with 403 KYC_VERIFICATION_REQUIRED until
     * the customer's KYC is verified.
     */
    @PostMapping("/accounts/{accountId}/activate")
    @PreAuthorize("hasAuthority('ACCOUNT_MANAGE')")
    public AccountResponse activate(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal CurrentUser staff,
            @Valid @RequestBody AccountStatusChangeRequest request
    ) {

        return accountService.activate(accountId, staff.customerId(), request.reason());
    }

    @PostMapping("/accounts/{accountId}/freeze")
    @PreAuthorize("hasAuthority('ACCOUNT_MANAGE')")
    public AccountResponse freeze(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal CurrentUser staff,
            @Valid @RequestBody AccountStatusChangeRequest request
    ) {

        return accountService.freeze(accountId, staff.customerId(), request.reason());
    }

    @PostMapping("/accounts/{accountId}/unfreeze")
    @PreAuthorize("hasAuthority('ACCOUNT_MANAGE')")
    public AccountResponse unfreeze(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal CurrentUser staff,
            @Valid @RequestBody AccountStatusChangeRequest request
    ) {

        return accountService.unfreeze(accountId, staff.customerId(), request.reason());
    }

    /**
     * POST rather than DELETE: the account is kept (closed, not removed)
     * and the reason travels in the body.
     */
    @PostMapping("/accounts/{accountId}/close")
    @PreAuthorize("hasAuthority('ACCOUNT_MANAGE')")
    public AccountResponse close(
            @PathVariable UUID accountId,
            @AuthenticationPrincipal CurrentUser staff,
            @Valid @RequestBody AccountStatusChangeRequest request
    ) {

        return accountService.close(accountId, staff.customerId(), request.reason());
    }
}
