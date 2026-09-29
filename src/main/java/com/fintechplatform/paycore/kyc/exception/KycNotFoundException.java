package com.fintechplatform.paycore.kyc.exception;

public class KycNotFoundException extends RuntimeException {

    public KycNotFoundException() {
        super("KYC profile not found");
    }
}
