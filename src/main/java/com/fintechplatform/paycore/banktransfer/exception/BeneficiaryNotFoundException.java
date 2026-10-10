package com.fintechplatform.paycore.banktransfer.exception;

public class BeneficiaryNotFoundException extends RuntimeException {

    public BeneficiaryNotFoundException() {
        super("No account with that number was found at the bank");
    }
}
