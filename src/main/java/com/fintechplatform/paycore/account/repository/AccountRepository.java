package com.fintechplatform.paycore.account.repository;

import com.fintechplatform.paycore.account.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository
        extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    /**
     * Looks an account up only if it belongs to the customer, so ownership
     * is part of the query rather than a check someone can forget.
     */
    Optional<Account> findByIdAndCustomerId(UUID id, UUID customerId);

    List<Account> findByCustomerIdOrderByCreatedAtAsc(UUID customerId);
}
