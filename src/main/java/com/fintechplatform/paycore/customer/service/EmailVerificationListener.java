package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.customer.event.CustomerEmailChangedEvent;
import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Sends a verification link once a registration or email change has
 * committed. A failure here is logged, never thrown: the registration has
 * already succeeded, and the customer can ask for another link.
 */
@Component
public class EmailVerificationListener {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationListener.class);

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationListener(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @TransactionalEventListener
    public void onCustomerRegistered(CustomerRegisteredEvent event) {
        sendLink(event.customerId());
    }

    @TransactionalEventListener
    public void onEmailChanged(CustomerEmailChangedEvent event) {
        sendLink(event.customerId());
    }

    private void sendLink(UUID customerId) {
        try {
            emailVerificationService.sendLink(customerId);
        } catch (RuntimeException exception) {
            log.error("Could not issue a verification link for customer {}", customerId, exception);
        }
    }
}
