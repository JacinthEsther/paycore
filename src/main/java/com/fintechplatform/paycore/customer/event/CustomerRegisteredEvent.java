package com.fintechplatform.paycore.customer.event;

import java.util.UUID;

/**
 * Published inside the registration transaction once the customer is
 * saved. Listeners run in that same transaction, so a listener failure
 * rolls the registration back.
 */
public record CustomerRegisteredEvent(
        UUID customerId
) {
}
