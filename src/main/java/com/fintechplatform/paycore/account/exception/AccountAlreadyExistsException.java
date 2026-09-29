package com.fintechplatform.paycore.account.exception;

import com.fintechplatform.paycore.account.enums.AccountType;

public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(AccountType type, String currency) {
        super("You already have an open " + type + " " + currency + " account");
    }
}
