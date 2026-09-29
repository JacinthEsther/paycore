package com.fintechplatform.paycore.kyc.exception;

public class KycAlreadyExistsException extends RuntimeException {

    public KycAlreadyExistsException() {
        super("KYC profile already exists");
    }
}
