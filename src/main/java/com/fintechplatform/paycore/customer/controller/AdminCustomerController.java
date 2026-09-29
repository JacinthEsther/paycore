package com.fintechplatform.paycore.customer.controller;

import com.fintechplatform.paycore.customer.dto.response.CustomerPageResponse;
import com.fintechplatform.paycore.customer.service.CustomerService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin customer directory. Actions on a single customer stay on
 * {@link CustomerController} and the admin role endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
@PreAuthorize("hasAuthority('CUSTOMER_READ')")
public class AdminCustomerController {

    private final CustomerService customerService;

    public AdminCustomerController(
            CustomerService customerService
    ) {
        this.customerService = customerService;
    }

    @GetMapping
    public CustomerPageResponse listCustomers(
            @RequestParam(required = false) String search,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater")
            int page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100")
            int size
    ) {

        return customerService.listCustomers(search, page, size);
    }
}
