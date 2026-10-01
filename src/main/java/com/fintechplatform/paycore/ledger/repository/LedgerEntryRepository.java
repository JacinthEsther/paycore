package com.fintechplatform.paycore.ledger.repository;

import com.fintechplatform.paycore.ledger.entity.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, UUID> {

    /**
     * In insertion order (ids are UUIDv7), with each entry's account so the
     * response can show account numbers without a query per entry.
     */
    @EntityGraph(attributePaths = "account")
    List<LedgerEntry> findByTransactionIdOrderByIdAsc(UUID transactionId);

    /**
     * Whether the transaction touches any of the customer's accounts: the
     * ownership check for reading it.
     */
    boolean existsByTransactionIdAndAccountCustomerId(UUID transactionId, UUID customerId);

    /**
     * CREDITS - DEBITS of everything before the instant: a statement's
     * opening balance.
     */
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

    /**
     * Credits, debits and line count for [from, to).
     */
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

    /**
     * One page of statement lines in [from, to), oldest first, with their
     * transactions. (createdAt, id) is a total order, so pages never
     * overlap or skip entries created in the same instant.
     */
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

    /**
     * CREDITS - DEBITS of the period's entries that come before the given
     * entry in statement order: carries the running balance onto a later
     * page without reading the earlier pages.
     */
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

    /**
     * Ids of the customer (not system) accounts a transaction touched.
     * Ids only, so no account is loaded before the caller locks it.
     */
    @Query("""
            SELECT e.account.id
            FROM LedgerEntry e
            WHERE e.transaction.id = :transactionId
              AND e.account.customer IS NOT NULL
            """)
    List<UUID> findCustomerAccountIds(@Param("transactionId") UUID transactionId);

    /**
     * Minor units credited to the account by provider deposits that still
     * stand: what counts towards the account's funding limit. Reversed
     * ones do not count; staff deposits and transfers never do.
     */
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

    /**
     * Minor units credited to the account by inbound transfers from the
     * provider that still stand.
     */
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

    /**
     * Every entry of the given transactions, with their accounts and
     * account holders: names the other side of each statement line in
     * one query.
     */
    @EntityGraph(attributePaths = {"account", "account.customer"})
    List<LedgerEntry> findByTransactionIdIn(List<UUID> transactionIds);

    /**
     * CREDITS - DEBITS in minor units: the balance is derived from the
     * ledger, never stored. Answered from idx_ledger_entries_account_created
     * alone (its INCLUDE columns), without reading table rows.
     */
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
