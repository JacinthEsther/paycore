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

    @Query("SELECT a.id FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<UUID> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    Optional<Account> findByIdAndCustomerId(UUID id, UUID customerId);

    List<Account> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);

    Optional<Account> findByTypeAndCurrencyAndCustomerIsNull(AccountType type, String currency);

    List<Account> findByCustomerIsNullOrderByTypeAscCurrencyAsc();

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :accountId AND a.customer.id = :customerId")
    Optional<Account> findByIdAndCustomerIdForUpdate(
            @Param("accountId") UUID accountId,
            @Param("customerId") UUID customerId
    );
}
