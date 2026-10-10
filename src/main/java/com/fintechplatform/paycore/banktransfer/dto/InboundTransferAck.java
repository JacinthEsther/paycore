package com.fintechplatform.paycore.banktransfer.dto;

public record InboundTransferAck(String status, String reference, String reason) {

    public static InboundTransferAck accepted(String reference) {
        return new InboundTransferAck("ACCEPTED", reference, null);
    }

    public static InboundTransferAck rejected(String reason) {
        return new InboundTransferAck("REJECTED", null, reason);
    }
}
