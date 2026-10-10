package com.fintechplatform.paycore.payment.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentStatus {

    CREATED,

    PROCESSING,

    UNKNOWN,

    NEEDS_REVIEW,

    SUCCEEDED,

    FAILED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public boolean isOpen() {
        return !isFinal();
    }

    public boolean canMoveTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(SUCCEEDED, FAILED, UNKNOWN, NEEDS_REVIEW);
            case UNKNOWN -> EnumSet.of(SUCCEEDED, FAILED, NEEDS_REVIEW);
            case NEEDS_REVIEW -> EnumSet.of(SUCCEEDED, FAILED);
            case SUCCEEDED, FAILED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }
}
