package com.fintechplatform.paycore.customer.controller;

import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.service.CustomerService;
import com.fintechplatform.paycore.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerProfileController {

    private final CustomerService customerService;

    public CustomerProfileController(
            CustomerService customerService
    ) {
        this.customerService =
                customerService;
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
}
