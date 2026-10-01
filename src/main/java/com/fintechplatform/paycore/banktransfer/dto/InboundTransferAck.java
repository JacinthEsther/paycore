package com.fintechplatform.paycore.banktransfer.dto;

/**
 * PayCore's answer to the rail: ACCEPTED with the ledger reference, or
 * REJECTED with a reason, in which case the rail returns the money to the
 * sending bank.
 */
public record InboundTransferAck(String status, String reference, String reason) {

    public static InboundTransferAck accepted(String reference) {
        return new InboundTransferAck("ACCEPTED", reference, null);
    }

    public static InboundTransferAck rejected(String reason) {
        return new InboundTransferAck("REJECTED", null, reason);
    }
}
