package com.fintechplatform.paycore.payment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paycore.payments.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryScheduler {

    private final PaymentRecoveryService recoveryService;

    public PaymentRecoveryScheduler(PaymentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(
            initialDelayString = "${paycore.payments.recovery.interval:PT30S}",
            fixedDelayString = "${paycore.payments.recovery.interval:PT30S}"
    )
    public void sweep() {
        recoveryService.recoverDue();
    }
}
