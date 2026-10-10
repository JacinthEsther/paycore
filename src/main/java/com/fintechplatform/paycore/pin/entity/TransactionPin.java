package com.fintechplatform.paycore.pin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transaction_pins")
public class TransactionPin {

    @Id
    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TransactionPin() {
    }

    public static TransactionPin create(UUID customerId, String pinHash) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(pinHash, "pinHash");

        TransactionPin pin = new TransactionPin();
        pin.customerId = customerId;
        pin.pinHash = pinHash;
        pin.createdAt = Instant.now();
        pin.updatedAt = pin.createdAt;
        return pin;
    }

    public boolean isLocked(int maxAttempts) {
        return failedAttempts >= maxAttempts;
    }

    public int attemptsRemaining(int maxAttempts) {
        return Math.max(0, maxAttempts - failedAttempts);
    }

    public void reserveAttempt() {
        failedAttempts++;
        touch();
    }

    public void attemptSucceeded() {
        failedAttempts = 0;
        touch();
    }

    public void attemptFailed(int maxAttempts) {
        if (isLocked(maxAttempts) && lockedAt == null) {
            lockedAt = Instant.now();
        }
        touch();
    }

    public void replace(String pinHash) {
        this.pinHash = Objects.requireNonNull(pinHash, "pinHash");
        failedAttempts = 0;
        lockedAt = null;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getPinHash() {
        return pinHash;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
