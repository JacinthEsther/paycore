package com.fintechplatform.paycore.operations.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;
import com.fintechplatform.paycore.operations.enums.OperationsRequestType;
import com.fintechplatform.paycore.operations.exception.FourEyesViolationException;
import com.fintechplatform.paycore.operations.exception.RequestAlreadyDecidedException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A correction one operations officer (the maker) asks for and a
 * different one (the checker) approves or rejects. Nothing moves until it
 * is approved; the approval posts the ledger transaction and records it
 * here.
 *
 * <pre>
 * PENDING --approve--> APPROVED (result transaction set)
 *         --reject---> REJECTED
 * </pre>
 */
@Entity
@Table(name = "operations_requests")
public class OperationsRequest {

    @Id
    @UuidV7
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private OperationsRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperationsRequestStatus status;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, updatable = false)
    private LedgerEntryType direction;

    @Column(name = "amount_minor", updatable = false)
    private Long amountMinor;

    @Column(length = 3, updatable = false)
    private String currency;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "customer_description", length = 500, updatable = false)
    private String customerDescription;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "result_transaction_id")
    private UUID resultTransactionId;

    @Version
    private long version;

    protected OperationsRequest() {
    }

    /** Credit (or debit) the account by the amount, against settlement. */
    public static OperationsRequest adjustment(
            UUID requestedBy,
            UUID accountId,
            LedgerEntryType direction,
            Money amount,
            String reason,
            String customerDescription
    ) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        OperationsRequest request = newRequest(OperationsRequestType.ADJUSTMENT, requestedBy, reason, customerDescription);
        request.accountId = Objects.requireNonNull(accountId, "accountId");
        request.direction = Objects.requireNonNull(direction, "direction");
        request.amountMinor = amount.amountMinor();
        request.currency = amount.currency().code();
        return request;
    }

    /** Undo the transaction. */
    public static OperationsRequest reversal(
            UUID requestedBy,
            UUID transactionId,
            String reason,
            String customerDescription
    ) {
        OperationsRequest request = newRequest(OperationsRequestType.REVERSAL, requestedBy, reason, customerDescription);
        request.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        return request;
    }

    private static OperationsRequest newRequest(
            OperationsRequestType type,
            UUID requestedBy,
            String reason,
            String customerDescription
    ) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }

        OperationsRequest request = new OperationsRequest();
        request.type = type;
        request.status = OperationsRequestStatus.PENDING;
        request.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        request.requestedAt = Instant.now();
        request.reason = reason.trim();
        request.customerDescription =
                customerDescription == null || customerDescription.isBlank() ? null : customerDescription.trim();
        return request;
    }

    /**
     * Checks that this officer may decide it: still pending, and not the
     * officer who asked for it.
     */
    public void requireDecidableBy(UUID officerId) {

        if (status != OperationsRequestStatus.PENDING) {
            throw new RequestAlreadyDecidedException(status);
        }

        if (requestedBy.equals(officerId)) {
            throw new FourEyesViolationException();
        }
    }

    public void approve(UUID checkerId, String note, UUID resultTransactionId) {

        requireDecidableBy(checkerId);

        this.status = OperationsRequestStatus.APPROVED;
        this.decidedBy = checkerId;
        this.decidedAt = Instant.now();
        this.decisionNote = normalize(note);
        this.resultTransactionId = Objects.requireNonNull(resultTransactionId, "resultTransactionId");
    }

    public void reject(UUID checkerId, String note) {

        requireDecidableBy(checkerId);

        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Say why the request is rejected");
        }

        this.status = OperationsRequestStatus.REJECTED;
        this.decidedBy = checkerId;
        this.decidedAt = Instant.now();
        this.decisionNote = note.trim();
    }

    /**
     * The ledger idempotency key of the transaction an approval posts: one
     * request can only ever post one transaction.
     */
    public String ledgerKey() {
        return "ops-request-" + id;
    }

    private static String normalize(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }

    public UUID getId() {
        return id;
    }

    public OperationsRequestType getType() {
        return type;
    }

    public OperationsRequestStatus getStatus() {
        return status;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public LedgerEntryType getDirection() {
        return direction;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getReason() {
        return reason;
    }

    public String getCustomerDescription() {
        return customerDescription;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public UUID getResultTransactionId() {
        return resultTransactionId;
    }
}
