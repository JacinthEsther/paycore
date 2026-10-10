package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.event.CustomerAccessRevokedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CustomerAccessRevocationListener {

    private final AuthenticationService authenticationService;

    public CustomerAccessRevocationListener(
            AuthenticationService authenticationService
    ) {
        this.authenticationService = authenticationService;
    }

    @EventListener
    public void onCustomerAccessRevoked(CustomerAccessRevokedEvent event) {
        authenticationService.logoutAll(event.customerId());
    }
}
