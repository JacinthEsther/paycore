package com.fintechplatform.paycore.account.controller;

import com.fintechplatform.paycore.account.dto.request.OpenAccountRequest;
import com.fintechplatform.paycore.account.dto.response.AccountResponse;
import com.fintechplatform.paycore.account.service.AccountService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Customer self-service. Every endpoint works on the caller's own accounts:
 * the customer id comes from the access token, never from the request, so
 * the permission (RBAC) and ownership are both enforced.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNT_OPEN')")
    public ResponseEntity<AccountResponse> openAccount(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody OpenAccountRequest request
    ) {

        AccountResponse account =
                accountService.openAccount(currentUser.customerId(), request);

        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + account.id()))
                .body(account);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public List<AccountResponse> getAccounts(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        return accountService.getOwnAccounts(currentUser.customerId());
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAuthority('ACCOUNT_READ')")
    public AccountResponse getAccount(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID accountId
    ) {

        return accountService.getOwnAccount(currentUser.customerId(), accountId);
    }
}
