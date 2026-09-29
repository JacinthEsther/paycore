package com.fintechplatform.paycore.identity.dto;

import com.fintechplatform.paycore.customer.enums.CustomerStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * What the per-request access token check needs about a login session and
 * its customer, read in one query.
 */
public record SessionAccess(
        UUID customerId,
        CustomerStatus customerStatus,
        Instant expiresAt,
        Instant revokedAt
) {

    /**
     * Whether an access token naming this session may be used by the given
     * customer: the session is theirs, not revoked, within its absolute
     * lifetime, and the customer can still authenticate. The idle timeout
     * needs no check here, because an access token expires well before a
     * session it was issued for can go idle.
     */
    public boolean grantsAccessTo(UUID customerId, Instant now) {

        return this.customerId.equals(customerId)
                && revokedAt == null
                && expiresAt.isAfter(now)
                && customerStatus.canAuthenticate();
    }
}
