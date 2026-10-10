package com.fintechplatform.paycore.ledger.repository;

import com.fintechplatform.paycore.ledger.entity.LedgerTransaction;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM LedgerTransaction t WHERE t.id = :transactionId")
    Optional<LedgerTransaction> findByIdForUpdate(@Param("transactionId") UUID transactionId);

    Optional<LedgerTransaction> findByInitiatedByAndIdempotencyKey(
            UUID initiatedBy,
            String idempotencyKey
    );

    Optional<LedgerTransaction> findByProviderAndProviderReference(String provider, String providerReference);

    List<LedgerTransaction> findByTypeAndProviderAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            LedgerTransactionType type, String provider, Instant from, Instant to
    );

    Optional<LedgerTransaction> findByReversesTransactionId(UUID reversesTransactionId);
}
