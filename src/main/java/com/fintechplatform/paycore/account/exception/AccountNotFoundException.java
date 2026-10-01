package com.fintechplatform.paycore.account.exception;

import java.util.UUID;

/**
 * Also thrown when the account exists but belongs to someone else, so a
 * customer cannot learn which account ids exist.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }

    private AccountNotFoundException(String message) {
        super(message);
    }

    public static AccountNotFoundException withAccountNumber(String accountNumber) {
        return new AccountNotFoundException("No account with number " + accountNumber);
    }
}
