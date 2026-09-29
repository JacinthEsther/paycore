package com.fintechplatform.paycore.customer.dto.response;

import com.fintechplatform.paycore.customer.enums.CustomerStatus;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        CustomerStatus status,
        boolean emailVerified,
        boolean phoneVerified,
        Instant createdAt,
        Instant updatedAt

) {
}