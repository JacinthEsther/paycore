package com.fintechplatform.paycore.customer.dto.response;

import com.fintechplatform.paycore.customer.enums.CustomerStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerSummaryResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        CustomerStatus status,
        List<String> roles,
        Instant createdAt
) {
}
