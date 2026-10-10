package com.fintechplatform.paycore.security;

import java.util.UUID;

public record CurrentUser(
        UUID customerId,
        UUID sessionId
) {

    public CurrentUser(UUID customerId) {
        this(customerId, null);
    }
}
