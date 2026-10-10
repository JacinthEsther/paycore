package com.fintechplatform.paycore.funding.provider;

import com.fintechplatform.paycore.funding.exception.FundingProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "paycore.funding.provider", havingValue = "simulated")
public class SimulatedFundingProvider implements FundingProvider {

    static final String PROVIDER_NAME = "SIMULATED";

    public static final long DECLINED_MINOR_ENDING = 99;

    public static final long CHARGED_THEN_TIMEOUT_MINOR_ENDING = 98;

    public static final long LOST_IN_TRANSIT_MINOR_ENDING = 97;

    public static final long STATUS_UNAVAILABLE_MINOR_ENDING = 96;

    public static final long OVERCHARGED_MINOR_ENDING = 94;

    public static final long UNSETTLED_MINOR_ENDING = 92;

    private static final Logger log =
            LoggerFactory.getLogger(SimulatedFundingProvider.class);

    private final CardPaymentRecords records;

    public SimulatedFundingProvider(CardPaymentRecords records) {
        this.records = records;

        log.warn(
                "Funding provider is SIMULATED: top-ups are not backed by real payments. "
                        + "Never use this with real customers."
        );
    }

    @Override
    public FundingResult collect(FundingCollection collection) {

        long kobo = collection.amount().amountMinor() % 100;

        if (records.find(collection.merchantReference()).isEmpty() && kobo == LOST_IN_TRANSIT_MINOR_ENDING) {
            throw new FundingProviderException(
                    PROVIDER_NAME + " timed out (simulated: amounts ending in .97 never arrive)", null
            );
        }

        boolean declined = kobo == DECLINED_MINOR_ENDING;

        CardPaymentRecord stored =
                records.saveIfAbsent(new CardPaymentRecord(
                        collection.merchantReference(),
                        "SIMPAY-" + UUID.randomUUID(),
                        collection.amount().amountMinor() + (kobo == OVERCHARGED_MINOR_ENDING ? 100 : 0),
                        collection.amount().currency().code(),
                        declined ? CardPaymentRecord.DECLINED : CardPaymentRecord.CONFIRMED,
                        declined ? "Card declined by the simulator (amounts ending in .99 are always declined)" : null,
                        null,
                        Instant.now(),
                        null,
                        kobo != UNSETTLED_MINOR_ENDING
                ));

        if (kobo == CHARGED_THEN_TIMEOUT_MINOR_ENDING || kobo == STATUS_UNAVAILABLE_MINOR_ENDING) {
            throw new FundingProviderException(
                    PROVIDER_NAME + " timed out after charging (simulated: amounts ending in ." + kobo + ")", null
            );
        }

        return CardPaymentRecord.DECLINED.equals(stored.status())
                ? FundingResult.declined(stored.providerReference(), stored.reason())
                : FundingResult.confirmed(stored.providerReference());
    }

    @Override
    public FundingStatus status(String merchantReference) {

        CardPaymentRecord record = records.find(merchantReference).orElse(null);

        if (record == null) {
            return FundingStatus.notFound();
        }

        if (record.amountMinor() % 100 == STATUS_UNAVAILABLE_MINOR_ENDING) {
            throw new FundingProviderException(
                    PROVIDER_NAME + " status query unavailable (simulated: amounts ending in .96)", null
            );
        }

        return switch (record.status()) {
            case CardPaymentRecord.DECLINED -> FundingStatus.declined(record.providerReference(), record.reason());
            case CardPaymentRecord.REFUNDED -> FundingStatus.refunded(record.providerReference(), record.refundReference());
            default -> FundingStatus.confirmed(record.providerReference());
        };
    }

    @Override
    public String refund(String merchantReference) {

        CardPaymentRecord record =
                records.find(merchantReference)
                        .orElseThrow(() -> new FundingProviderException(
                                PROVIDER_NAME + " has no charge " + merchantReference + " to refund", null
                        ));

        if (CardPaymentRecord.DECLINED.equals(record.status())) {
            throw new FundingProviderException(
                    PROVIDER_NAME + " charge " + merchantReference + " was declined; there is nothing to refund", null
            );
        }

        return records
                .markRefunded(merchantReference, "SIMREF-" + UUID.randomUUID(), Instant.now())
                .refundReference();
    }

    @Override
    public List<CardSettlementLine> settlementReport(Instant from, Instant to) {

        return records.between(from, to).stream()
                .filter(CardPaymentRecord::settled)
                .map(record -> new CardSettlementLine(
                        record.merchantReference(), record.providerReference(), record.amountMinor(),
                        record.currency(), record.status(), record.refundReference(), record.createdAt()
                ))
                .toList();
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
