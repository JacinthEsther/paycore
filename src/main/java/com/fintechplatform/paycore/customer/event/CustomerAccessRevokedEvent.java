package com.fintechplatform.paycore.customer.event;

import java.util.UUID;

public record CustomerAccessRevokedEvent(
        UUID customerId
) {
}
