package com.fintechplatform.paycore.funding.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * In-process stand-in for a payment provider, for the public Developer
 * Preview and local development. It never calls out and no real money
 * moves: it pretends the customer paid by card and confirms at once.
 *
 * <p>Any amount ending in .99 is declined, so the failed-payment path can
 * be tried as easily as the happy one. It must never be enabled where
 * real customers hold accounts: it creates balances nothing pays for.
 */
@Component
@ConditionalOnProperty(name = "paycore.funding.provider", havingValue = "simulated")
public class SimulatedFundingProvider implements FundingProvider {

    static final String PROVIDER_NAME = "SIMULATED";

    /** The minor-unit ending that is always declined. */
    public static final long DECLINED_MINOR_ENDING = 99;

    private static final Logger log =
            LoggerFactory.getLogger(SimulatedFundingProvider.class);

    public SimulatedFundingProvider() {
        log.warn(
                "Funding provider is SIMULATED: top-ups are not backed by real payments. "
                        + "Never use this with real customers."
        );
    }

    @Override
    public FundingResult collect(FundingCollection collection) {

        String providerReference = "SIMPAY-" + UUID.randomUUID();

        if (collection.amount().amountMinor() % 100 == DECLINED_MINOR_ENDING) {
            return FundingResult.declined(
                    providerReference,
                    "Card declined by the simulator (amounts ending in .99 are always declined)"
            );
        }

        return FundingResult.confirmed(providerReference);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String narration() {
        return "Top-up by card (simulated)";
    }
}
