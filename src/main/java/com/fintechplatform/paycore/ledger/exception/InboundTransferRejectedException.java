package com.fintechplatform.paycore.ledger.exception;

/**
 * PayCore cannot accept an inbound transfer (unknown or inactive account,
 * wrong currency). Nothing was credited; the rail returns the money to
 * the sending bank.
 */
public class InboundTransferRejectedException extends RuntimeException {

    public InboundTransferRejectedException(String reason) {
        super(reason);
    }
}
