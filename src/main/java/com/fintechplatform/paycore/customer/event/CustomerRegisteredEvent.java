package com.fintechplatform.paycore.customer.event;

import java.util.UUID;

public record CustomerRegisteredEvent(
        UUID customerId
) {
}
