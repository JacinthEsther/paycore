package com.fintechplatform.paycore.banktransfer.exception;

/** Name enquiry found no account with that number at that bank. */
public class BeneficiaryNotFoundException extends RuntimeException {

    public BeneficiaryNotFoundException() {
        super("No account with that number was found at the bank");
    }
}
