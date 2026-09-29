package com.fintechplatform.paycore.account.entity;

import com.fintechplatform.paycore.account.enums.AccountEventType;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of an account being opened or changing status, with
 * who did it and why. Ids are stored as plain UUIDs so reading the history
 * never loads accounts or customers. Rows are never updated or deleted.
 */
@Entity
@Table(name = "account_status_events")
public class AccountStatusEvent {

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private AccountEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30, updatable = false)
    private AccountStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private AccountStatus toStatus;

    @Column(name = "performed_by", nullable = false, updatable = false)
    private UUID performedBy;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AccountStatusEvent() {
    }

    private AccountStatusEvent(
            UUID accountId,
            AccountEventType eventType,
            AccountStatus fromStatus,
            AccountStatus toStatus,
            UUID performedBy,
            String reason
    ) {
        this.accountId = accountId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.performedBy = performedBy;
        this.reason = reason;
        this.occurredAt = Instant.now();
    }

    public static AccountStatusEvent opened(Account account, UUID customerId) {
        return new AccountStatusEvent(
                account.getId(),
                AccountEventType.OPENED,
                null,
                account.getStatus(),
                customerId,
                null
        );
    }

    public static AccountStatusEvent statusChanged(
            Account account,
            AccountEventType eventType,
            AccountStatus fromStatus,
            UUID performedBy,
            String reason
    ) {
        return new AccountStatusEvent(
                account.getId(),
                eventType,
                fromStatus,
                account.getStatus(),
                performedBy,
                reason
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public AccountEventType getEventType() {
        return eventType;
    }

    public AccountStatus getFromStatus() {
        return fromStatus;
    }

    public AccountStatus getToStatus() {
        return toStatus;
    }

    public UUID getPerformedBy() {
        return performedBy;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
