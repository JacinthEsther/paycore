package com.fintechplatform.paycore.customer.event;

import java.util.UUID;

/**
 * A customer's email address changed and is no longer verified; a new
 * verification link goes to the new address.
 */
public record CustomerEmailChangedEvent(UUID customerId) {
}
