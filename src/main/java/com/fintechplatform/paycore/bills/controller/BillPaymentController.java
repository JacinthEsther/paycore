package com.fintechplatform.paycore.bills.controller;

import com.fintechplatform.paycore.bills.dto.BillPaymentRequest;
import com.fintechplatform.paycore.bills.dto.BillPaymentResponse;
import com.fintechplatform.paycore.bills.dto.BillProductResponse;
import com.fintechplatform.paycore.bills.dto.BillerResponse;
import com.fintechplatform.paycore.bills.dto.VerifyBillCustomerRequest;
import com.fintechplatform.paycore.bills.dto.VerifyBillCustomerResponse;
import com.fintechplatform.paycore.bills.service.BillPaymentService;
import com.fintechplatform.paycore.pin.service.TransactionAuthorizationService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BillPaymentController {

    private final BillPaymentService billPaymentService;
    private final TransactionAuthorizationService transactionAuthorization;

    public BillPaymentController(
            BillPaymentService billPaymentService,
            TransactionAuthorizationService transactionAuthorization
    ) {
        this.billPaymentService = billPaymentService;
        this.transactionAuthorization = transactionAuthorization;
    }

    @GetMapping("/bills/billers")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public List<BillerResponse> billers() {
        return billPaymentService.billers();
    }

    @GetMapping("/bills/billers/{billerId}/products")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public List<BillProductResponse> products(@PathVariable String billerId) {
        return billPaymentService.products(billerId);
    }

    @PostMapping("/bills/verify")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public VerifyBillCustomerResponse verify(@Valid @RequestBody VerifyBillCustomerRequest request) {
        return billPaymentService.verify(request.billerId(), request.productCode(), request.customerNumber());
    }

    @PostMapping("/ledger/accounts/{sourceAccountId}/bill-payments")
    @PreAuthorize("hasAuthority('TRANSACTION_CREATE')")
    public ResponseEntity<BillPaymentResponse> pay(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID sourceAccountId,
            @Valid @RequestBody BillPaymentRequest request,
            @RequestHeader(name = TransactionAuthorizationService.PIN_HEADER, required = false) String pin
    ) {

        transactionAuthorization.authorize(currentUser, pin);

        BillPaymentResponse bill = billPaymentService.pay(currentUser.customerId(), sourceAccountId, request);

        return ResponseEntity
                .created(URI.create("/api/v1/ledger/transactions/" + bill.transaction().id()))
                .body(bill);
    }
}
