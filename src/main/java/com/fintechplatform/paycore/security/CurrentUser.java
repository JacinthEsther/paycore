package com.fintechplatform.paycore.security;

import java.util.UUID;

/**
 * The authenticated principal resolved from the access token's
 * {@code sub} claim.
 */
public record CurrentUser(
        UUID customerId
) {
}
