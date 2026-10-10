package com.fintechplatform.paycore.funding.provider;

public record FundingResult(
        boolean confirmed,
        String providerReference,
        String reason
) {

    public static FundingResult confirmed(String providerReference) {
        return new FundingResult(true, providerReference, null);
    }

    public static FundingResult declined(String providerReference, String reason) {
        return new FundingResult(false, providerReference, reason);
    }
}
