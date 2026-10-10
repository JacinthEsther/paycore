package com.fintechplatform.paycore.verification.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.verification.enums.OneTimeCodePurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "one_time_codes")
public class OneTimeCode {

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private OneTimeCodePurpose purpose;

    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(name = "code_hash", nullable = false, length = 64, updatable = false)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected OneTimeCode() {
    }

    public static OneTimeCode issue(
            UUID customerId,
            OneTimeCodePurpose purpose,
            UUID subjectId,
            String codeHash,
            Duration timeToLive
    ) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(codeHash, "codeHash");

        OneTimeCode code = new OneTimeCode();
        code.customerId = customerId;
        code.purpose = purpose;
        code.subjectId = subjectId;
        code.codeHash = codeHash;
        code.createdAt = Instant.now();
        code.expiresAt = code.createdAt.plus(timeToLive);
        return code;
    }

    public boolean isUsable(Instant now, int maxAttempts) {
        return usedAt == null && attempts < maxAttempts && now.isBefore(expiresAt);
    }

    public void recordFailedAttempt() {
        attempts++;
    }

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

    public OneTimeCodePurpose getPurpose() {
        return purpose;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public int getAttempts() {
        return attempts;
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
