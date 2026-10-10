package com.fintechplatform.paycore.kyc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paycore.kyc.tier-upgrade.auto-approve", havingValue = "true", matchIfMissing = true)
public class KycTierUpgradeScheduler {

    private static final Logger log = LoggerFactory.getLogger(KycTierUpgradeScheduler.class);

    private final KycTierService tierService;

    public KycTierUpgradeScheduler(KycTierService tierService) {
        this.tierService = tierService;
    }

    @Scheduled(
            fixedDelayString = "${paycore.kyc.tier-upgrade.check-interval:PT15M}",
            initialDelayString = "PT1M"
    )
    public void decideDueUpgrades() {

        for (UUID upgradeId : tierService.dueForAutomaticDecision()) {
            try {
                tierService.decideAutomatically(upgradeId);
            } catch (RuntimeException exception) {
                log.error("Automatic decision on tier upgrade {} failed: {}", upgradeId, exception.getMessage(), exception);
            }
        }
    }
}
