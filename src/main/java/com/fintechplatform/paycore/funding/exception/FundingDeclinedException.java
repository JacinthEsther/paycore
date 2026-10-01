package com.fintechplatform.paycore.funding.exception;

/**
 * The provider answered and refused the payment. Nothing was credited.
 */
public class FundingDeclinedException extends RuntimeException {

    private final String providerReference;

    public FundingDeclinedException(String reason, String providerReference) {
        super(reason == null || reason.isBlank() ? "The payment was declined" : reason);
        this.providerReference = providerReference;
    }

    public String getProviderReference() {
        return providerReference;
    }
}
