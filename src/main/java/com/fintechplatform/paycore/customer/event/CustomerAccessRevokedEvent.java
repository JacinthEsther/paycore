package com.fintechplatform.paycore.customer.event;

import java.util.UUID;

/**
 * Published inside the suspend or close transaction once the customer can
 * no longer authenticate. Listeners run in that same transaction, so the
 * status change and ending the customer's sessions commit together.
 */
public record CustomerAccessRevokedEvent(
        UUID customerId
) {
}
