package com.fintechplatform.paycore.ledger.exception;

public class InboundTransferRejectedException extends RuntimeException {

    public InboundTransferRejectedException(String reason) {
        super(reason);
    }
}
