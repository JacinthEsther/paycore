package com.fintechplatform.paycore.payment.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentResolution {

    COMPLETE,
    REVERSE,
    CREDIT,
    REFUND,
    CLOSE;

    public static Set<PaymentResolution> allowedFor(PaymentType type) {
        return switch (type) {
            case OUTBOUND_TRANSFER, BILL_PAYMENT -> EnumSet.of(COMPLETE, REVERSE);
            case CARD_FUNDING -> EnumSet.of(CREDIT, REFUND, CLOSE);
        };
    }

    public boolean succeeds() {
        return this == COMPLETE || this == CREDIT;
    }
}
