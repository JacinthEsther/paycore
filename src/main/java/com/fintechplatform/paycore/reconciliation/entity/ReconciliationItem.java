package com.fintechplatform.paycore.reconciliation.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.reconciliation.enums.ItemKind;
import com.fintechplatform.paycore.reconciliation.enums.ReconciliationResult;
import com.fintechplatform.paycore.reconciliation.enums.ResolutionStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_items")
public class ReconciliationItem {

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ItemKind kind;

    @Column(name = "external_reference", nullable = false, length = 100, updatable = false)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ReconciliationResult result;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "provider_amount_minor", updatable = false)
    private Long providerAmountMinor;

    @Column(name = "provider_status", length = 20, updatable = false)
    private String providerStatus;

    @Column(name = "paycore_amount_minor", updatable = false)
    private Long paycoreAmountMinor;

    @Column(name = "paycore_status", length = 20, updatable = false)
    private String paycoreStatus;

    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    @Column(name = "ledger_transaction_id", updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    @Column(length = 500, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", nullable = false, length = 20)
    private ResolutionStatus resolutionStatus;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationItem() {
    }

    public record Side(Long amountMinor, String status) {

        public static final Side MISSING = new Side(null, null);

        public boolean present() {
            return status != null;
        }
    }

    public record Links(UUID paymentId, UUID ledgerTransactionId, UUID accountId) {

        public static final Links NONE = new Links(null, null, null);
    }

    public ReconciliationItem(
            UUID runId,
            ItemKind kind,
            String externalReference,
            ReconciliationResult result,
            String currency,
            Side provider,
            Side paycore,
            Links links,
            String detail
    ) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.externalReference = Objects.requireNonNull(externalReference, "externalReference");
        this.result = Objects.requireNonNull(result, "result");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.providerAmountMinor = provider.amountMinor();
        this.providerStatus = provider.status();
        this.paycoreAmountMinor = paycore.amountMinor();
        this.paycoreStatus = paycore.status();
        this.paymentId = links.paymentId();
        this.ledgerTransactionId = links.ledgerTransactionId();
        this.accountId = links.accountId();
        this.detail = detail;
        this.resolutionStatus = result.isBreak() ? ResolutionStatus.OPEN : ResolutionStatus.NOT_REQUIRED;
        this.createdAt = Instant.now();
    }

    public void resolve(UUID resolvedBy, String note) {

        if (resolutionStatus != ResolutionStatus.OPEN) {
            throw new IllegalArgumentException(
                    resolutionStatus == ResolutionStatus.RESOLVED
                            ? "This break is already resolved"
                            : "A match has nothing to resolve"
            );
        }

        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Say how the break was resolved");
        }

        this.resolutionStatus = ResolutionStatus.RESOLVED;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = Instant.now();
        this.resolutionNote = note.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public ItemKind getKind() {
        return kind;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public ReconciliationResult getResult() {
        return result;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getProviderAmountMinor() {
        return providerAmountMinor;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public Long getPaycoreAmountMinor() {
        return paycoreAmountMinor;
    }

    public String getPaycoreStatus() {
        return paycoreStatus;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getDetail() {
        return detail;
    }

    public ResolutionStatus getResolutionStatus() {
        return resolutionStatus;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
