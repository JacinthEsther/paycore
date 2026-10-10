package com.fintechplatform.paycore.funding.provider;

public record FundingStatus(Outcome outcome, String providerReference, String reason, String refundReference) {

    public enum Outcome {
        CONFIRMED,
        DECLINED,
        REFUNDED,
        PENDING,
        NOT_FOUND
    }

    public static FundingStatus confirmed(String providerReference) {
        return new FundingStatus(Outcome.CONFIRMED, providerReference, null, null);
    }

    public static FundingStatus declined(String providerReference, String reason) {
        return new FundingStatus(Outcome.DECLINED, providerReference, reason, null);
    }

    public static FundingStatus refunded(String providerReference, String refundReference) {
        return new FundingStatus(Outcome.REFUNDED, providerReference, null, refundReference);
    }

    public static FundingStatus pending() {
        return new FundingStatus(Outcome.PENDING, null, null, null);
    }

    public static FundingStatus notFound() {
        return new FundingStatus(Outcome.NOT_FOUND, null, null, null);
    }
}
