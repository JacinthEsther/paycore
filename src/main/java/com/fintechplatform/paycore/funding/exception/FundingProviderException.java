package com.fintechplatform.paycore.funding.exception;

/**
 * The provider could not give an answer (outage, bad credentials), as
 * opposed to declining the payment. Details are logged, not returned.
 */
public class FundingProviderException extends RuntimeException {

    public FundingProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
