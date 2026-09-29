package com.fintechplatform.paycore.kyc.provider;

/**
 * The only KYC vendor contract the domain knows. Implementations
 * (Dojah and the demo simulator today; Smile Identity, Youverify, ...
 * later) translate vendor responses into {@link KycProviderResult}.
 * Exactly one is active, chosen by {@code paycore.kyc.provider}.
 */
public interface KycProvider {

    /**
     * @throws com.fintechplatform.paycore.kyc.exception.KycProviderException
     *         when the provider cannot give an answer (outage, bad
     *         credentials, rate limit), as opposed to a failed check
     */
    KycProviderResult verifyBvn(
            KycVerificationRequest request
    );

    /**
     * Same contract as {@link #verifyBvn}, for a National Identification
     * Number.
     */
    KycProviderResult verifyNin(
            KycVerificationRequest request
    );

    String providerName();
}
