package com.fintechplatform.paycore.identity.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.customer.entity.Customer;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "login_sessions",
        indexes = {
                @Index(
                        name = "idx_login_sessions_customer",
                        columnList = "customer_id"
                ),
                @Index(
                        name = "idx_login_sessions_token_hash",
                        columnList = "session_token_hash"
                )
        }
)
public class LoginSession {

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false
    )
    private Customer customer;

    @Column(
            name = "session_token_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String sessionTokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 1000)
    private String userAgent;

    protected LoginSession() {
    }

    public static LoginSession create(
            Customer customer,
            String sessionTokenHash,
            Instant expiresAt,
            String ipAddress,
            String userAgent
    ) {
        Instant now = Instant.now();

        LoginSession session = new LoginSession();

        session.customer = customer;
        session.sessionTokenHash = sessionTokenHash;
        session.createdAt = now;
        session.expiresAt = expiresAt;
        session.lastUsedAt = now;
        session.ipAddress = ipAddress;
        session.userAgent = userAgent;

        return session;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * True when the session has not been used for longer than
     * {@code idleTimeout}.
     */
    public boolean isIdle(Duration idleTimeout) {
        return Instant.now().isAfter(lastUsedAt.plus(idleTimeout));
    }

    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }

    public void revoke() {
        if (!isRevoked()) {
            revokedAt = Instant.now();
        }
    }

    public void markUsed() {
        if (!isActive()) {
            throw new IllegalStateException(
                    "Cannot use inactive session"
            );
        }

        lastUsedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getSessionTokenHash() {
        return sessionTokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }
}