package com.fintechplatform.paycore.ledger.entity;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One accounting side of a transaction: a positive amount debited from or
 * credited to one account. Entries are historical evidence, so there are
 * no setters and every column is insert-only; a mistake is corrected with
 * a new, compensating transaction.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10, updatable = false)
    private LedgerEntryType entryType;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public static LedgerEntry debit(LedgerTransaction transaction, Account account, Money amount) {
        return new LedgerEntry(transaction, account, LedgerEntryType.DEBIT, amount);
    }

    public static LedgerEntry credit(LedgerTransaction transaction, Account account, Money amount) {
        return new LedgerEntry(transaction, account, LedgerEntryType.CREDIT, amount);
    }

    private LedgerEntry(
            LedgerTransaction transaction,
            Account account,
            LedgerEntryType entryType,
            Money amount
    ) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");

        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Ledger entry amount must be positive");
        }

        String code = amount.currency().code();

        if (!code.equals(transaction.getCurrency())) {
            throw new IllegalArgumentException(
                    "Entry currency " + code + " differs from transaction currency "
                            + transaction.getCurrency()
            );
        }

        if (!code.equals(account.getCurrency())) {
            throw new IllegalArgumentException(
                    "Entry currency " + code + " differs from account currency "
                            + account.getCurrency()
            );
        }

        this.transaction = transaction;
        this.account = account;
        this.entryType = entryType;
        this.amountMinor = amount.amountMinor();
        this.currency = code;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
