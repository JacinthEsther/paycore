package com.fintechplatform.paycore.customer.controller;


import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import com.fintechplatform.paycore.customer.dto.request.UpdateCustomerRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(
            CustomerService customerService
    ) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> register(
            @Valid
            @RequestBody
            RegisterCustomerRequest request
    ) {

        CustomerResponse response =
                customerService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }


    @GetMapping("/{customerId}")
    @PreAuthorize("hasAuthority('CUSTOMER_READ')")
    public ResponseEntity<CustomerResponse> getCustomer(
            @PathVariable UUID customerId
    ) {

        return ResponseEntity.ok(
                customerService.getCustomer(customerId)
        );
    }


    @PatchMapping("/{customerId}")
    @PreAuthorize("hasAuthority('CUSTOMER_UPDATE')")
    public ResponseEntity<CustomerResponse> updateProfile(
            @PathVariable UUID customerId,

            @Valid
            @RequestBody
            UpdateCustomerRequest request
    ) {

        return ResponseEntity.ok(
                customerService.updateProfile(
                        customerId,
                        request
                )
        );
    }


    @PostMapping("/{customerId}/suspend")
    @PreAuthorize("hasAuthority('CUSTOMER_SUSPEND')")
    public ResponseEntity<CustomerResponse> suspend(
            @PathVariable UUID customerId
    ) {

        return ResponseEntity.ok(
                customerService.suspendCustomer(
                        customerId
                )
        );
    }


    @PostMapping("/{customerId}/reactivate")
    @PreAuthorize("hasAuthority('CUSTOMER_SUSPEND')")
    public ResponseEntity<CustomerResponse> reactivate(
            @PathVariable UUID customerId
    ) {

        return ResponseEntity.ok(
                customerService.reactivateCustomer(
                        customerId
                )
        );
    }


    @DeleteMapping("/{customerId}")
    @PreAuthorize("hasAuthority('CUSTOMER_CLOSE')")
    public ResponseEntity<Void> closeAccount(
            @PathVariable UUID customerId
    ) {

        customerService.closeCustomer(customerId);

        return ResponseEntity.noContent().build();
    }
}