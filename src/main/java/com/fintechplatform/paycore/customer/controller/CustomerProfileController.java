package com.fintechplatform.paycore.customer.controller;

import com.fintechplatform.paycore.customer.dto.request.UpdateCustomerRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.service.CustomerService;
import com.fintechplatform.paycore.customer.service.EmailVerificationService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerProfileController {

    private final CustomerService customerService;
    private final EmailVerificationService emailVerificationService;

    public CustomerProfileController(
            CustomerService customerService,
            EmailVerificationService emailVerificationService
    ) {
        this.customerService = customerService;
        this.emailVerificationService = emailVerificationService;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('PROFILE_READ')")
    public CustomerResponse me(
            @AuthenticationPrincipal
            CurrentUser currentUser
    ) {

        return customerService
                .getCustomer(currentUser.customerId());
    }

    @PatchMapping("/me")
    @PreAuthorize("hasAuthority('PROFILE_UPDATE')")
    public CustomerResponse updateMe(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateCustomerRequest request
    ) {

        return customerService.updateProfile(currentUser.customerId(), request);
    }

    @PostMapping("/me/verification-email")
    @PreAuthorize("hasAuthority('PROFILE_UPDATE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerificationEmail(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        emailVerificationService.resend(currentUser.customerId());
    }
}
