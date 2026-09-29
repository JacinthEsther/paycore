package com.fintechplatform.paycore.account.exception;

public class KycVerificationRequiredException extends RuntimeException {

    public KycVerificationRequiredException() {
        super("KYC must be verified before an account can be opened");
    }
}
