package com.fintechplatform.paycore.ledger.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.enums.LedgerEntryType;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @UuidV7
    private UUID id;

    @Column(nullable = false, length = 100, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private LedgerTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerTransactionStatus status;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "initiated_by", updatable = false)
    private UUID initiatedBy;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(length = 500, updatable = false)
    private String description;

    @Column(name = "staff_note", length = 500, updatable = false)
    private String staffNote;

    @Column(name = "approved_by", updatable = false)
    private UUID approvedBy;

    @Column(name = "provider", length = 30, updatable = false)
    private String provider;

    @Column(name = "provider_reference", length = 100, updatable = false)
    private String providerReference;

    @Column(name = "counterparty_name", length = 200, updatable = false)
    private String counterpartyName;

    @Column(name = "counterparty_bank", length = 100, updatable = false)
    private String counterpartyBank;

    @Column(name = "counterparty_account_number", length = 20, updatable = false)
    private String counterpartyAccountNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reverses_transaction_id", updatable = false)
    private UUID reversesTransactionId;

    protected LedgerTransaction() {
    }

    public static LedgerTransaction createTransfer(
            String reference,
            String currency,
            UUID customerId,
            String idempotencyKey,
            String description
    ) {
        return newTransaction(
                reference, LedgerTransactionType.TRANSFER, currency,
                Objects.requireNonNull(customerId, "customerId"), idempotencyKey, description
        );
    }

    public static LedgerTransaction createProviderDeposit(
            String reference,
            String currency,
            UUID customerId,
            String idempotencyKey,
            String description,
            String provider,
            String providerReference
    ) {
        LedgerTransaction deposit =
                newTransaction(
                        reference, LedgerTransactionType.DEPOSIT, currency,
                        Objects.requireNonNull(customerId, "customerId"), idempotencyKey, description
                );

        deposit.setProvider(provider, providerReference);
        return deposit;
    }

    public static LedgerTransaction createInboundTransfer(
            String reference,
            String currency,
            String description,
            String provider,
            String sessionId,
            Counterparty sender
    ) {
        LedgerTransaction inbound =
                newTransaction(reference, LedgerTransactionType.INBOUND_TRANSFER, currency, null, sessionId, description);

        inbound.setProvider(provider, sessionId);
        inbound.setCounterparty(sender);
        return inbound;
    }

    public static LedgerTransaction createOutboundTransfer(
            String reference,
            String currency,
            UUID customerId,
            String idempotencyKey,
            String description,
            String provider,
            String sessionId,
            Counterparty beneficiary
    ) {
        LedgerTransaction outbound =
                newTransaction(
                        reference, LedgerTransactionType.OUTBOUND_TRANSFER, currency,
                        Objects.requireNonNull(customerId, "customerId"), idempotencyKey, description
                );

        outbound.setProvider(provider, sessionId);
        outbound.setCounterparty(beneficiary);
        return outbound;
    }

    public static LedgerTransaction createBillPayment(
            String reference,
            String currency,
            UUID customerId,
            String idempotencyKey,
            String description,
            String provider,
            String requestId,
            Counterparty biller
    ) {
        LedgerTransaction bill =
                newTransaction(
                        reference, LedgerTransactionType.BILL_PAYMENT, currency,
                        Objects.requireNonNull(customerId, "customerId"), idempotencyKey, description
                );

        bill.setProvider(provider, requestId);
        bill.setCounterparty(biller);
        return bill;
    }

    public static LedgerTransaction createAdjustment(
            String reference,
            String currency,
            UUID requestedBy,
            UUID approvedBy,
            String idempotencyKey,
            String description,
            String staffNote
    ) {
        LedgerTransaction adjustment =
                newTransaction(
                        reference, LedgerTransactionType.ADJUSTMENT, currency,
                        Objects.requireNonNull(requestedBy, "requestedBy"), idempotencyKey, description
                );

        adjustment.setApproval(requestedBy, approvedBy, staffNote);
        return adjustment;
    }

    public static LedgerTransaction createReversal(
            String reference,
            LedgerTransaction original,
            UUID requestedBy,
            UUID approvedBy,
            String idempotencyKey,
            String description,
            String staffNote
    ) {
        LedgerTransaction reversal = newReversal(reference, original, requestedBy, idempotencyKey, description);

        reversal.setApproval(requestedBy, approvedBy, staffNote);
        return reversal;
    }

    public static LedgerTransaction createRailReversal(
            String reference,
            LedgerTransaction original,
            String idempotencyKey,
            String description,
            String providerReference
    ) {
        if (original.type != LedgerTransactionType.OUTBOUND_TRANSFER
                && original.type != LedgerTransactionType.BILL_PAYMENT) {
            throw new IllegalArgumentException("Only a payment to a provider is reversed by the provider");
        }

        LedgerTransaction reversal =
                newReversal(reference, original, original.initiatedBy, idempotencyKey, description);

        reversal.setProvider(original.provider, providerReference);
        reversal.setCounterparty(
                new Counterparty(
                        original.counterpartyName,
                        original.counterpartyBank,
                        original.counterpartyAccountNumber
                )
        );
        return reversal;
    }

    private static LedgerTransaction newReversal(
            String reference,
            LedgerTransaction original,
            UUID initiatedBy,
            String idempotencyKey,
            String description
    ) {
        Objects.requireNonNull(original, "original");

        if (original.type == LedgerTransactionType.REVERSAL) {
            throw new IllegalStateException("A reversal cannot itself be reversed");
        }

        if (original.status != LedgerTransactionStatus.POSTED) {
            throw new IllegalStateException("Only a posted transaction can be reversed");
        }

        LedgerTransaction reversal =
                newTransaction(
                        reference,
                        LedgerTransactionType.REVERSAL,
                        original.currency,
                        Objects.requireNonNull(initiatedBy, "initiatedBy"),
                        idempotencyKey,
                        description
                );

        reversal.reversesTransactionId = Objects.requireNonNull(original.id, "original.id");
        return reversal;
    }

    private static LedgerTransaction newTransaction(
            String reference,
            LedgerTransactionType type,
            String currency,
            UUID initiatedBy,
            String idempotencyKey,
            String description
    ) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "Currency must be a 3-letter upper-case ISO 4217 code"
            );
        }

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.reference = reference;
        transaction.type = type;
        transaction.status = LedgerTransactionStatus.INITIATED;
        transaction.currency = currency;
        transaction.initiatedBy = initiatedBy;
        transaction.idempotencyKey = idempotencyKey;
        transaction.description = description;
        transaction.createdAt = Instant.now();
        return transaction;
    }

    private void setProvider(String provider, String providerReference) {

        if (provider == null || provider.isBlank() || providerReference == null || providerReference.isBlank()) {
            throw new IllegalArgumentException(type + " needs the provider and its reference");
        }

        this.provider = provider;
        this.providerReference = providerReference;
    }

    private void setCounterparty(Counterparty counterparty) {

        Objects.requireNonNull(counterparty, "counterparty");

        this.counterpartyName = counterparty.name();
        this.counterpartyBank = counterparty.bank();
        this.counterpartyAccountNumber = counterparty.accountNumber();
    }

    private void setApproval(UUID requestedBy, UUID approvedBy, String staffNote) {

        Objects.requireNonNull(approvedBy, "approvedBy");

        if (approvedBy.equals(requestedBy)) {
            throw new IllegalArgumentException("The officer who requested a correction cannot approve it");
        }

        if (staffNote == null || staffNote.isBlank()) {
            throw new IllegalArgumentException(type + " transactions need a staff note");
        }

        this.approvedBy = approvedBy;
        this.staffNote = staffNote;
    }

    public void post(List<LedgerEntry> entries) {

        if (status != LedgerTransactionStatus.INITIATED) {
            throw new IllegalStateException("Only an initiated transaction can be posted");
        }

        if (entries == null || entries.size() < 2) {
            throw new IllegalStateException("A ledger transaction needs at least two entries");
        }

        long debits = 0;
        long credits = 0;

        for (LedgerEntry entry : entries) {

            if (entry.getTransaction() != this) {
                throw new IllegalStateException("Entry belongs to a different transaction");
            }

            if (!currency.equals(entry.getCurrency())) {
                throw new IllegalStateException(
                        "Entry currency " + entry.getCurrency()
                                + " differs from transaction currency " + currency
                );
            }

            if (entry.getEntryType() == LedgerEntryType.DEBIT) {
                debits = Math.addExact(debits, entry.getAmountMinor());
            } else {
                credits = Math.addExact(credits, entry.getAmountMinor());
            }
        }

        if (debits != credits) {
            throw new IllegalStateException(
                    "Ledger transaction is not balanced: debits " + debits + ", credits " + credits
            );
        }

        status = LedgerTransactionStatus.POSTED;
        postedAt = Instant.now();
    }

    public void markReversed() {

        if (status != LedgerTransactionStatus.POSTED) {
            throw new IllegalStateException("Only a posted transaction can be reversed");
        }

        status = LedgerTransactionStatus.REVERSED;
        reversedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public LedgerTransactionType getType() {
        return type;
    }

    public LedgerTransactionStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getInitiatedBy() {
        return initiatedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDescription() {
        return description;
    }

    public String getStaffNote() {
        return staffNote;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Counterparty getCounterparty() {
        return counterpartyName == null
                ? null
                : new Counterparty(counterpartyName, counterpartyBank, counterpartyAccountNumber);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public UUID getReversesTransactionId() {
        return reversesTransactionId;
    }

    public boolean isReversible() {
        return type != LedgerTransactionType.REVERSAL && status == LedgerTransactionStatus.POSTED;
    }
}
