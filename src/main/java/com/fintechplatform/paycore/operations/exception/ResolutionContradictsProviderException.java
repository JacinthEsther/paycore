package com.fintechplatform.paycore.operations.exception;

import com.fintechplatform.paycore.payment.enums.PaymentResolution;

public class ResolutionContradictsProviderException extends RuntimeException {

    public ResolutionContradictsProviderException(PaymentResolution resolution, String providerAnswer) {
        super("Cannot " + resolution + ": the provider now says " + providerAnswer
                + ". Run a status check instead; it applies the provider's answer.");
    }
}
