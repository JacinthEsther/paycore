package com.fintechplatform.paycore.account.service;

/**
 * Produces candidate account numbers. A candidate is not guaranteed to be
 * unused: {@link AccountService} checks it and the database unique
 * constraint has the final say.
 */
public interface AccountNumberGenerator {

    String generate();
}
