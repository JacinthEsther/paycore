package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.customer.event.CustomerRegisteredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class KycRegistrationListener {

    private final KycService kycService;

    public KycRegistrationListener(KycService kycService) {
        this.kycService = kycService;
    }

    @EventListener
    public void onCustomerRegistered(CustomerRegisteredEvent event) {
        kycService.createInitialProfile(event.customerId());
    }
}
