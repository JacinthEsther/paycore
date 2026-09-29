package com.fintechplatform.paycore.authorization.dto;

import java.util.List;
import java.util.UUID;

public record CustomerRolesResponse(
        UUID customerId,
        List<String> roles
) {
}
