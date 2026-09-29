package com.fintechplatform.paycore.account.enums;

/**
 * Account lifecycle. Transitions are enforced by
 * {@link com.fintechplatform.paycore.account.entity.Account}, never by
 * setting a status directly:
 *
 * <pre>
 * PENDING --activate--> ACTIVE <--freeze/unfreeze--> FROZEN
 *    |                    |                            |
 *    +-------------------close-------------------------+--> CLOSED (final)
 * </pre>
 *
 * Accounts open PENDING and become ACTIVE once the customer is eligible,
 * so account, customer and KYC status stay independent.
 */
public enum AccountStatus {
    PENDING,
    ACTIVE,
    FROZEN,
    CLOSED
}
