package com.fintechplatform.paycore.account.entity;

import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.enums.AccountType;
import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.customer.entity.Customer;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Entity
@Table(
        name = "accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_accounts_account_number",
                        columnNames = "account_number"
                )
        }
)
public class Account {

    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", updatable = false)
    private Customer customer;

    @Column(name = "account_number", length = 10, updatable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30, updatable = false)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccountStatus status;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Account() {
    }

    public static Account open(
            Customer customer,
            String accountNumber,
            AccountType type,
            String currency
    ) {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(type, "type");

        if (type.isSystem()) {
            throw new IllegalArgumentException(type + " is a system account and cannot be opened");
        }

        if (accountNumber == null || !ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw new IllegalArgumentException("Account number must be 10 digits");
        }

        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException(
                    "Currency must be a 3-letter upper-case ISO 4217 code"
            );
        }

        Account account = new Account();
        account.customer = customer;
        account.accountNumber = accountNumber;
        account.type = type;
        account.currency = currency;
        account.status = AccountStatus.PENDING;
        account.createdAt = Instant.now();
        account.updatedAt = account.createdAt;

        return account;
    }

    public void activate() {

        if (status != AccountStatus.PENDING) {
            throw new IllegalStateException("Only pending accounts can be activated");
        }

        status = AccountStatus.ACTIVE;
        touch();
    }

    public void freeze() {

        if (status != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Only active accounts can be frozen");
        }

        status = AccountStatus.FROZEN;
        touch();
    }

    public void unfreeze() {

        if (status != AccountStatus.FROZEN) {
            throw new IllegalStateException("Only frozen accounts can be unfrozen");
        }

        status = AccountStatus.ACTIVE;
        touch();
    }

    public void close() {

        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("Account is already closed");
        }

        status = AccountStatus.CLOSED;
        closedAt = Instant.now();
        touch();
    }

    public boolean isOwnedBy(UUID customerId) {
        return customer != null && customer.getId().equals(customerId);
    }

    public boolean isSystemAccount() {
        return type.isSystem();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public AccountType getType() {
        return type;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
