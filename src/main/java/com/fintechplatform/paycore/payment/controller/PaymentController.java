package com.fintechplatform.paycore.payment.controller;

import com.fintechplatform.paycore.payment.dto.PaymentResponse;
import com.fintechplatform.paycore.payment.service.PaymentService;
import com.fintechplatform.paycore.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public PaymentResponse get(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID paymentId
    ) {
        return paymentService.getOwn(currentUser.customerId(), paymentId);
    }

    @GetMapping("/reference/{reference}")
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public PaymentResponse getByReference(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable String reference
    ) {
        return paymentService.getOwnByReference(currentUser.customerId(), reference);
    }
}
