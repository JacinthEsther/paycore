package com.fintechplatform.paycore.funding.exception;

public class FundingDisabledException extends RuntimeException {

    public FundingDisabledException() {
        super("Adding money is not available: no payment provider is configured");
    }
}
