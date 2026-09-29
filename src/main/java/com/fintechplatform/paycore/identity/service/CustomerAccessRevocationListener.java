package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.event.CustomerAccessRevokedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ends every session and refresh token of a customer who was suspended or
 * closed, so reactivating the account later does not bring old sessions
 * back: the customer has to sign in again.
 */
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
