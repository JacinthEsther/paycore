package com.fintechplatform.paycore.identity.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.customer.entity.Customer;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refresh_token_hash",
                columnNames = "token_hash"
        )
)
public class RefreshToken {

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private LoginSession session;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /**
     * Every token produced by rotating the same login's refresh token
     * shares one family, so reuse of any old token can revoke them all.
     */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {
    }

    public static RefreshToken create(
            Customer customer,
            LoginSession session,
            String tokenHash,
            UUID familyId,
            Instant expiresAt
    ) {
        RefreshToken token = new RefreshToken();

        token.customer = customer;
        token.session = session;
        token.tokenHash = tokenHash;
        token.familyId = familyId;
        token.createdAt = Instant.now();
        token.expiresAt = expiresAt;

        return token;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return !isRevoked() && !isExpired();
    }

    public void revoke() {
        if (!isRevoked()) {
            revokedAt = Instant.now();
        }
    }

    public void replaceWith(RefreshToken replacement) {

        if (!isActive()) {
            throw new IllegalStateException(
                    "Cannot rotate an inactive refresh token"
            );
        }

        replacedBy = replacement.getId();
        revoke();
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public LoginSession getSession() {
        return session;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }
}
