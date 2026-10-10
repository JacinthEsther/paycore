package com.fintechplatform.paycore.funding.provider;

import java.time.Instant;
import java.util.List;

public interface FundingProvider {

    FundingResult collect(FundingCollection collection);

    FundingStatus status(String merchantReference);

    String refund(String merchantReference);

    List<CardSettlementLine> settlementReport(Instant from, Instant to);

    String providerName();

    String narration();
}
