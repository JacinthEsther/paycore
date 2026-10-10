package com.fintechplatform.paycore.authorization.dto;

import com.fintechplatform.paycore.authorization.enums.RoleAssignmentAction;

import java.time.Instant;
import java.util.UUID;

public record RoleAssignmentEventResponse(
        UUID id,
        String role,
        RoleAssignmentAction action,
        UUID performedBy,
        String reason,
        Instant occurredAt
) {
}
