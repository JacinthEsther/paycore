package com.fintechplatform.paycore.kyc.enums;

/**
 * What the verification provider returned. Distinct from
 * {@link KycStatus}, which tracks where the customer is in our process.
 */
public enum VerificationResult {

    PENDING,

    PASSED,

    FAILED,

    REQUIRES_REVIEW
}
