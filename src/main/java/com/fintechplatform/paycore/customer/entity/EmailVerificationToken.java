package com.fintechplatform.paycore.customer.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A one-time link proving a customer owns an email address. Only the
 * SHA-256 hash of the token is stored; the raw token exists only in the
 * email that was sent.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, length = 255, updatable = false)
    private String email;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected EmailVerificationToken() {
    }

    public static EmailVerificationToken issue(
            UUID customerId,
            String email,
            String tokenHash,
            Duration timeToLive
    ) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(tokenHash, "tokenHash");

        EmailVerificationToken token = new EmailVerificationToken();
        token.customerId = customerId;
        token.email = email;
        token.tokenHash = tokenHash;
        token.createdAt = Instant.now();
        token.expiresAt = token.createdAt.plus(timeToLive);
        return token;
    }

    /**
     * Usable: not used or replaced, and not expired.
     */
    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    /** Used, or replaced by a newer link. Either way it no longer works. */
    public void markUsed() {
        if (usedAt == null) {
            usedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
