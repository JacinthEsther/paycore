package com.fintechplatform.paycore.ledger.repository;

import com.fintechplatform.paycore.ledger.entity.LedgerEntry;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, UUID> {

    @EntityGraph(attributePaths = "account")
    List<LedgerEntry> findByTransactionIdOrderByIdAsc(UUID transactionId);

    boolean existsByTransactionIdAndAccountCustomerId(UUID transactionId, UUID customerId);

    @Query("""
            SELECT COALESCE(
                SUM(
                    CASE
                        WHEN e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.CREDIT
                        THEN e.amountMinor
                        ELSE -e.amountMinor
                    END
                ),
                0L
            )
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.createdAt < :before
            """)
    long calculateBalanceBefore(
            @Param("accountId") UUID accountId,
            @Param("before") Instant before
    );

    @Query("""
            SELECT new com.fintechplatform.paycore.ledger.repository.StatementTotals(
                COALESCE(SUM(
                    CASE
                        WHEN e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.CREDIT
                        THEN e.amountMinor
                        ELSE 0L
                    END
                ), 0L),
                COALESCE(SUM(
                    CASE
                        WHEN e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.DEBIT
                        THEN e.amountMinor
                        ELSE 0L
                    END
                ), 0L),
                COUNT(e)
            )
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.createdAt >= :from
              AND e.createdAt < :to
            """)
    StatementTotals calculateStatementTotals(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @EntityGraph(attributePaths = "transaction")
    @Query("""
            SELECT e
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.createdAt >= :from
              AND e.createdAt < :to
            ORDER BY e.createdAt ASC, e.id ASC
            """)
    List<LedgerEntry> findStatementLines(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable
    );

    @Query("""
            SELECT COALESCE(
                SUM(
                    CASE
                        WHEN e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.CREDIT
                        THEN e.amountMinor
                        ELSE -e.amountMinor
                    END
                ),
                0L
            )
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.createdAt >= :from
              AND (e.createdAt < :createdAt OR (e.createdAt = :createdAt AND e.id < :entryId))
            """)
    long calculatePeriodBalanceBefore(
            @Param("accountId") UUID accountId,
            @Param("from") Instant from,
            @Param("createdAt") Instant createdAt,
            @Param("entryId") UUID entryId
    );

    @Query("""
            SELECT e.account.id
            FROM LedgerEntry e
            WHERE e.transaction.id = :transactionId
              AND e.account.customer IS NOT NULL
            """)
    List<UUID> findCustomerAccountIds(@Param("transactionId") UUID transactionId);

    @Query("""
            SELECT COALESCE(SUM(e.amountMinor), 0L)
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.CREDIT
              AND e.transaction.type = com.fintechplatform.paycore.ledger.enums.LedgerTransactionType.DEPOSIT
              AND e.transaction.status = com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus.POSTED
              AND e.transaction.provider IS NOT NULL
            """)
    long calculateProviderFunded(@Param("accountId") UUID accountId);

    @Query("""
            SELECT COALESCE(SUM(e.amountMinor), 0L)
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.CREDIT
              AND e.transaction.type = com.fintechplatform.paycore.ledger.enums.LedgerTransactionType.INBOUND_TRANSFER
              AND e.transaction.status = com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus.POSTED
              AND e.transaction.provider = :provider
            """)
    long calculateInboundReceived(@Param("accountId") UUID accountId, @Param("provider") String provider);

    @Query("""
            SELECT COALESCE(SUM(e.amountMinor), 0L)
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.DEBIT
              AND e.transaction.type IN :types
              AND e.transaction.status = com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus.POSTED
              AND e.createdAt >= :since
            """)
    long calculateSentSince(
            @Param("accountId") UUID accountId,
            @Param("types") Collection<LedgerTransactionType> types,
            @Param("since") Instant since
    );

    @EntityGraph(attributePaths = {"account", "account.customer"})
    List<LedgerEntry> findByTransactionIdIn(List<UUID> transactionIds);

    @Query("""
            SELECT COALESCE(
                SUM(
                    CASE
                        WHEN e.entryType = com.fintechplatform.paycore.ledger.enums.LedgerEntryType.CREDIT
                        THEN e.amountMinor
                        ELSE -e.amountMinor
                    END
                ),
                0L
            )
            FROM LedgerEntry e
            WHERE e.account.id = :accountId
              AND e.currency = :currency
            """)
    long calculateBalance(
            @Param("accountId") UUID accountId,
            @Param("currency") String currency
    );
}
