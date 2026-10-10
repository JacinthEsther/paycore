package com.fintechplatform.paycore.payment.controller;

import com.fintechplatform.paycore.payment.dto.PaymentEventResponse;
import com.fintechplatform.paycore.payment.dto.PaymentPageResponse;
import com.fintechplatform.paycore.payment.dto.PaymentResponse;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.service.PaymentRecoveryService;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/payments")
public class AdminPaymentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentService paymentService;
    private final PaymentRecoveryService recoveryService;

    public AdminPaymentController(PaymentService paymentService, PaymentRecoveryService recoveryService) {
        this.paymentService = paymentService;
        this.recoveryService = recoveryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public PaymentPageResponse list(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {

        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }

        return PaymentPageResponse.of(
                paymentService.list(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
        );
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public PaymentResponse get(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.get(paymentId));
    }

    @GetMapping("/reference/{reference}")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public PaymentResponse getByReference(@PathVariable String reference) {
        return paymentService.getByReference(reference);
    }

    @GetMapping("/{paymentId}/events")
    @PreAuthorize("hasAuthority('ACCOUNT_VIEW_ALL')")
    public List<PaymentEventResponse> events(@PathVariable UUID paymentId) {
        return paymentService.events(paymentId);
    }

    @PostMapping("/{paymentId}/escalate")
    @PreAuthorize("hasAuthority('LEDGER_REQUEST')")
    public PaymentResponse escalate(@PathVariable UUID paymentId) {
        return PaymentResponse.from(paymentService.escalate(paymentId));
    }

    @PostMapping("/{paymentId}/status-check")
    @PreAuthorize("hasAuthority('LEDGER_REQUEST')")
    public PaymentResponse checkStatus(@PathVariable UUID paymentId) {
        return recoveryService.checkNow(paymentId);
    }
}
