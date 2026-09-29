package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Every customer needs KYC before they can transact, so the profile is
 * created with the customer (NOT_STARTED) instead of on a separate call.
 * The customer module only publishes the event and knows nothing of KYC.
 */
@Component
public class KycRegistrationListener {

    private final KycService kycService;

    public KycRegistrationListener(KycService kycService) {
        this.kycService = kycService;
    }

    @EventListener
    public void onCustomerRegistered(CustomerRegisteredEvent event) {
        kycService.createKyc(event.customerId());
    }
}
