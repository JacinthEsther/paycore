package com.fintechplatform.paycore.account.repository;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.enums.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository
        extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    /**
     * The id only, without loading the account: a caller that goes on to
     * lock it must not already hold an unlocked copy, which Hibernate would
     * keep instead of the state read under the lock.
     */
    @Query("SELECT a.id FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<UUID> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /**
     * Looks an account up only if it belongs to the customer, so ownership
     * is part of the query rather than a check someone can forget.
     */
    Optional<Account> findByIdAndCustomerId(UUID id, UUID customerId);

    List<Account> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    Optional<Account> findByTypeAndCurrencyAndCustomerIsNull(AccountType type, String currency);

    List<Account> findByCustomerIsNullOrderByTypeAscCurrencyAsc();

    /**
     * Creates the system account of this type and currency unless it
     * already exists. ON CONFLICT against uk_accounts_system_type_currency
     * makes concurrent first uses safe, and unlike a failed INSERT it does
     * not abort the surrounding transaction.
     */
    @Modifying
    @Query(
            value = """
                    INSERT INTO accounts
                        (id, customer_id, account_number, account_type, status, currency,
                         created_at, updated_at, version)
                    VALUES
                        (paycore_uuid_v7(), NULL, NULL, :type, 'ACTIVE', :currency, now(), now(), 0)
                    ON CONFLICT (account_type, currency) WHERE customer_id IS NULL
                    DO NOTHING
                    """,
            nativeQuery = true
    )
    int createSystemAccountIfMissing(@Param("type") String type, @Param("currency") String currency);

    /**
     * SELECT ... FOR UPDATE: holds the row lock until the transaction ends,
     * so two transfers from the same account run one after the other and
     * the second sees the first one's debit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") UUID accountId);

    /**
     * As {@link #findByIdForUpdate}, but only the customer's own account, so
     * the ownership check and the lock happen in one statement.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :accountId AND a.customer.id = :customerId")
    Optional<Account> findByIdAndCustomerIdForUpdate(
            @Param("accountId") UUID accountId,
            @Param("customerId") UUID customerId
    );
}
