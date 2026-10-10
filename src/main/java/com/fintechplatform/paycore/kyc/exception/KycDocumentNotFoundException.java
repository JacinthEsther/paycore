package com.fintechplatform.paycore.kyc.exception;

public class KycDocumentNotFoundException extends RuntimeException {

    public KycDocumentNotFoundException() {
        super("KYC document not found");
    }
}
