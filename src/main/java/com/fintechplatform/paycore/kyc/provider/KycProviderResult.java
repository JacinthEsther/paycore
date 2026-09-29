package com.fintechplatform.paycore.kyc.provider;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;

public record KycProviderResult(
        String provider,
        String providerReference,
        VerificationResult result,
        String reason
) {
}
