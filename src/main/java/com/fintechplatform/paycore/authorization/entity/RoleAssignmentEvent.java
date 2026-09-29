package com.fintechplatform.paycore.authorization.entity;

import com.fintechplatform.paycore.authorization.enums.RoleAssignmentAction;
import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a role being assigned to or revoked from a
 * customer. Ids are stored as plain UUIDs so reading the history never
 * loads customers or roles. Rows are never updated or deleted.
 */
@Entity
@Table(name = "role_assignment_events")
public class RoleAssignmentEvent {

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private RoleAssignmentAction action;

    /**
     * The customer (admin) who made the change, or null when the system
     * did, e.g. the default CUSTOMER role on registration.
     */
    @Column(name = "performed_by", updatable = false)
    private UUID performedBy;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected RoleAssignmentEvent() {
    }

    private RoleAssignmentEvent(
            UUID customerId,
            UUID roleId,
            RoleAssignmentAction action,
            UUID performedBy,
            String reason
    ) {
        this.customerId = customerId;
        this.roleId = roleId;
        this.action = action;
        this.performedBy = performedBy;
        this.reason = reason;
        this.occurredAt = Instant.now();
    }

    public static RoleAssignmentEvent assigned(
            UUID customerId,
            UUID roleId,
            UUID performedBy,
            String reason
    ) {
        return new RoleAssignmentEvent(
                customerId,
                roleId,
                RoleAssignmentAction.ASSIGNED,
                performedBy,
                reason
        );
    }

    public static RoleAssignmentEvent revoked(
            UUID customerId,
            UUID roleId,
            UUID performedBy,
            String reason
    ) {
        return new RoleAssignmentEvent(
                customerId,
                roleId,
                RoleAssignmentAction.REVOKED,
                performedBy,
                reason
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getRoleId() {
        return roleId;
    }

    public RoleAssignmentAction getAction() {
        return action;
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
