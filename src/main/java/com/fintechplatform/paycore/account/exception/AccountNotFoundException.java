package com.fintechplatform.paycore.account.exception;

import java.util.UUID;

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
