package com.fintechplatform.paycore.reconciliation.service;

import com.fintechplatform.paycore.banktransfer.rail.BankRail;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.reconciliation.enums.ProviderKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "paycore.reconciliation.scheduled-runs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final ObjectProvider<BankRail> rails;
    private final ObjectProvider<FundingProvider> fundingProviders;

    public ReconciliationScheduler(
            ReconciliationService reconciliationService,
            ObjectProvider<BankRail> rails,
            ObjectProvider<FundingProvider> fundingProviders
    ) {
        this.reconciliationService = reconciliationService;
        this.rails = rails;
        this.fundingProviders = fundingProviders;
    }

    @Scheduled(
            cron = "${paycore.reconciliation.schedule:0 30 6 * * *}",
            zone = "${paycore.ledger.statement-time-zone:Africa/Lagos}"
    )
    public void reconcileYesterday() {

        LocalDate yesterday = reconciliationService.today().minusDays(1);

        if (rails.getIfAvailable() != null) {
            reconcile(ProviderKind.BANK_RAIL, yesterday);
        }

        if (fundingProviders.getIfAvailable() != null) {
            reconcile(ProviderKind.CARD, yesterday);
        }
    }

    private void reconcile(ProviderKind kind, LocalDate day) {

        try {
            reconciliationService.run(kind, day, null);
        } catch (RuntimeException exception) {
            log.error("Scheduled reconciliation of {} for {} failed: {}", kind, day, exception.getMessage(), exception);
        }
    }
}
