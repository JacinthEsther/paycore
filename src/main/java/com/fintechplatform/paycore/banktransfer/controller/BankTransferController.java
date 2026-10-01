package com.fintechplatform.paycore.banktransfer.controller;

import com.fintechplatform.paycore.banktransfer.dto.BankResponse;
import com.fintechplatform.paycore.banktransfer.dto.NameEnquiryRequest;
import com.fintechplatform.paycore.banktransfer.dto.NameEnquiryResponse;
import com.fintechplatform.paycore.banktransfer.dto.OutboundTransferRequest;
import com.fintechplatform.paycore.banktransfer.dto.OutboundTransferResponse;
import com.fintechplatform.paycore.banktransfer.service.BankTransferService;
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
 * Banks a customer can pay, who holds an account (name enquiry), and
 * transfers to other banks from the caller's own accounts.
 */
@RestController
@RequestMapping("/api/v1")
public class BankTransferController {

    private final BankTransferService bankTransferService;

    public BankTransferController(BankTransferService bankTransferService) {
        this.bankTransferService = bankTransferService;
    }

    /** PayCore first, then every bank the rail reaches. */
    @GetMapping("/banks")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public List<BankResponse> banks() {
        return bankTransferService.banks();
    }

    /** 422 BENEFICIARY_NOT_FOUND if the bank has no such account. */
    @PostMapping("/banks/name-enquiry")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public NameEnquiryResponse nameEnquiry(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody NameEnquiryRequest request
    ) {
        return bankTransferService.nameEnquiry(currentUser.customerId(), request.bankCode(), request.accountNumber());
    }

    /**
     * 201 with the debit and the rail's verdict. A rejected transfer is
     * still 201: the debit happened and was reversed, and transferStatus
     * says FAILED.
     */
    @PostMapping("/ledger/accounts/{sourceAccountId}/bank-transfers")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public ResponseEntity<OutboundTransferResponse> send(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID sourceAccountId,
            @Valid @RequestBody OutboundTransferRequest request
    ) {

        OutboundTransferResponse transfer =
                bankTransferService.send(currentUser.customerId(), sourceAccountId, request);

        return ResponseEntity
                .created(URI.create("/api/v1/ledger/transactions/" + transfer.transaction().id()))
                .body(transfer);
    }
}
