package com.fintechplatform.paycore.banktransfer.exception;

public class BankTransfersDisabledException extends RuntimeException {

    public BankTransfersDisabledException() {
        super("Transfers to and from other banks are not available: no bank rail is configured");
    }
}
