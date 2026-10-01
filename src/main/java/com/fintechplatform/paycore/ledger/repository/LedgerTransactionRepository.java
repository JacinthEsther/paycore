package com.fintechplatform.paycore.ledger.repository;

import com.fintechplatform.paycore.ledger.entity.LedgerTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByReference(String reference);

    /**
     * SELECT ... FOR UPDATE on the original of a reversal, so two
     * reversals of the same transaction run one after the other and the
     * second sees it already REVERSED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM LedgerTransaction t WHERE t.id = :transactionId")
    Optional<LedgerTransaction> findByIdForUpdate(@Param("transactionId") UUID transactionId);

    /**
     * Idempotency keys are scoped to the customer who used them.
     */
    Optional<LedgerTransaction> findByInitiatedByAndIdempotencyKey(
            UUID initiatedBy,
            String idempotencyKey
    );

    /**
     * A provider payment or rail session already recorded; unique, so a
     * payment reported twice is found rather than credited again.
     */
    Optional<LedgerTransaction> findByProviderAndProviderReference(String provider, String providerReference);

    /** The reversal of a transaction, if it has one (at most one). */
    Optional<LedgerTransaction> findByReversesTransactionId(UUID reversesTransactionId);
}
