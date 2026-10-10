package com.fintechplatform.paycore.payment.dto;

import com.fintechplatform.paycore.payment.entity.PaymentEvent;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;

import java.time.Instant;

public record PaymentEventResponse(
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        int statusChecks,
        String failureReason,
        Instant at
) {

    public static PaymentEventResponse from(PaymentEvent event) {
        return new PaymentEventResponse(
                event.getFromStatus(),
                event.getToStatus(),
                event.getStatusChecks(),
                event.getFailureReason(),
                event.getCreatedAt()
        );
    }
}
