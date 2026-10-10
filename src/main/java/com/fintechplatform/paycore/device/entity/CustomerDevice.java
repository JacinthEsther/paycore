package com.fintechplatform.paycore.device.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.device.enums.DeviceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_devices")
public class CustomerDevice {

    private static final int LABEL_LENGTH = 200;

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "device_hash", nullable = false, length = 64, updatable = false)
    private String deviceHash;

    @Column(length = LABEL_LENGTH)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "trusted_at")
    private Instant trustedAt;

    @Column(name = "cooling_off_until")
    private Instant coolingOffUntil;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CustomerDevice() {
    }

    public static CustomerDevice firstDevice(UUID customerId, String deviceHash, String label) {
        CustomerDevice device = newDevice(customerId, deviceHash, label);
        device.status = DeviceStatus.TRUSTED;
        device.trustedAt = device.createdAt;
        return device;
    }

    public static CustomerDevice pending(UUID customerId, String deviceHash, String label) {
        CustomerDevice device = newDevice(customerId, deviceHash, label);
        device.status = DeviceStatus.PENDING;
        return device;
    }

    private static CustomerDevice newDevice(UUID customerId, String deviceHash, String label) {
        CustomerDevice device = new CustomerDevice();
        device.customerId = customerId;
        device.deviceHash = deviceHash;
        device.label = truncate(label);
        device.createdAt = Instant.now();
        device.lastSeenAt = device.createdAt;
        return device;
    }

    public void trust(Instant coolingOffUntil) {
        if (status != DeviceStatus.PENDING) {
            throw new IllegalStateException("Only a pending device can be confirmed");
        }
        status = DeviceStatus.TRUSTED;
        trustedAt = Instant.now();
        this.coolingOffUntil = coolingOffUntil;
    }

    public void revoke() {
        if (status != DeviceStatus.REVOKED) {
            status = DeviceStatus.REVOKED;
            revokedAt = Instant.now();
        }
    }

    public void signedIn(String label) {
        lastSeenAt = Instant.now();
        if (label != null) {
            this.label = truncate(label);
        }
        if (status == DeviceStatus.REVOKED) {
            status = DeviceStatus.PENDING;
            trustedAt = null;
            coolingOffUntil = null;
            revokedAt = null;
        }
    }

    public boolean isTrusted() {
        return status == DeviceStatus.TRUSTED;
    }

    public boolean isCoolingOff(Instant now) {
        return coolingOffUntil != null && now.isBefore(coolingOffUntil);
    }

    private static String truncate(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.length() > LABEL_LENGTH ? trimmed.substring(0, LABEL_LENGTH) : trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getDeviceHash() {
        return deviceHash;
    }

    public String getLabel() {
        return label;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getTrustedAt() {
        return trustedAt;
    }

    public Instant getCoolingOffUntil() {
        return coolingOffUntil;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
