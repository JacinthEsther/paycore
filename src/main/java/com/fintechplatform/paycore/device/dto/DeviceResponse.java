package com.fintechplatform.paycore.device.dto;

import com.fintechplatform.paycore.device.entity.CustomerDevice;
import com.fintechplatform.paycore.device.enums.DeviceStatus;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
        UUID id,
        String label,
        DeviceStatus status,
        boolean current,
        Instant createdAt,
        Instant lastSeenAt,
        Instant trustedAt,
        Instant coolingOffUntil
) {

    public static DeviceResponse of(CustomerDevice device, boolean current, Instant now) {
        return new DeviceResponse(
                device.getId(),
                device.getLabel(),
                device.getStatus(),
                current,
                device.getCreatedAt(),
                device.getLastSeenAt(),
                device.getTrustedAt(),
                device.isCoolingOff(now) ? device.getCoolingOffUntil() : null
        );
    }
}
