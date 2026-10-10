package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.customer.enums.CustomerStatus;

import java.time.Instant;
import java.util.UUID;

public record SessionAccess(
        UUID customerId,
        CustomerStatus customerStatus,
        Instant expiresAt,
        Instant revokedAt
) {

    public boolean grantsAccessTo(UUID customerId, Instant now) {

        return this.customerId.equals(customerId)
                && revokedAt == null
                && expiresAt.isAfter(now)
                && customerStatus.canAuthenticate();
    }
}
