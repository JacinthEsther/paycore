package com.fintechplatform.paycore.kyc.provider;

public interface KycProvider {

    KycProviderResult verifyBvn(
            KycVerificationRequest request
    );

    KycProviderResult verifyNin(
            KycVerificationRequest request
    );

    String providerName();
}
