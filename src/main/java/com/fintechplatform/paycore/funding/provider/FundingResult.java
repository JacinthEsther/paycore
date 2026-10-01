package com.fintechplatform.paycore.funding.provider;

/**
 * The provider's answer. providerReference identifies the payment at the
 * provider; reason says why a payment was declined (null when confirmed).
 */
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
