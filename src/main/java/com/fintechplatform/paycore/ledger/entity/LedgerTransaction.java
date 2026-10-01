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

/**
 * One financial event, e.g. a transfer. The money itself is in its
 * {@link LedgerEntry entries}; this records what happened, who asked for
 * it and where it is in its lifecycle.
 *
 * Every kind of transaction has its own factory, which sets exactly the
 * fields that kind needs (the database checks the same rules):
 *
 * <pre>
 * TRANSFER           customer to customer inside PayCore
 * DEPOSIT            card top-up a payment provider confirmed
 * INBOUND_TRANSFER   from another bank, reported by the bank rail
 * OUTBOUND_TRANSFER  to another bank, sent through the bank rail
 * ADJUSTMENT         manual correction, requested and approved by two officers
 * REVERSAL           undoes one of the above
 * </pre>
 *
 * There are no setters. The status only moves through {@link #post} and
 * {@link #markReversed}, and posting is where the double-entry rules are
 * enforced.
 */
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

    /**
     * Who asked for it: the customer for their own transfers and top-ups,
     * the officer who made the request for staff corrections. Null only
     * for an inbound transfer, which nobody at PayCore initiated.
     */
    @Column(name = "initiated_by", updatable = false)
    private UUID initiatedBy;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    /** Customer-facing narration, shown on transactions and statements. */
    @Column(length = 500, updatable = false)
    private String description;

    /**
     * Why staff moved money. Internal: never part of a customer response.
     * Required for adjustments and staff reversals.
     */
    @Column(name = "staff_note", length = 500, updatable = false)
    private String staffNote;

    /** For a staff correction, the officer who approved it (never the initiator). */
    @Column(name = "approved_by", updatable = false)
    private UUID approvedBy;

    /**
     * For anything a payment provider or bank rail carried: which one, and
     * its own reference (payment reference, NIP session id).
     */
    @Column(name = "provider", length = 30, updatable = false)
    private String provider;

    @Column(name = "provider_reference", length = 100, updatable = false)
    private String providerReference;

    /** For transfers to or from another bank: the other side. */
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

    /** For a REVERSAL, the transaction it undoes; null otherwise. */
    @Column(name = "reverses_transaction_id", updatable = false)
    private UUID reversesTransactionId;

    protected LedgerTransaction() {
    }

    // ============================================================
    // FACTORIES
    // ============================================================

    /** A customer's transfer to another PayCore account. */
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

    /**
     * A card top-up the customer asked for and a payment provider
     * confirmed. The provider and its payment reference record where the
     * money came from.
     */
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

    /**
     * Money another bank sent to a PayCore account, as the bank rail
     * reported it. Nobody at PayCore initiated it; the rail's session id
     * is both its provider reference and its idempotency key.
     */
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

    /**
     * A customer's transfer to an account at another bank, sent through
     * the bank rail under the given session id.
     */
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

    /**
     * A manual correction: requested by one operations officer, approved
     * by another, with the reason as the staff note.
     */
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

    /**
     * A staff REVERSAL of a posted transaction: requested by one officer,
     * approved by another. Its entries must be the original's with the
     * sides swapped; posting it and marking the original reversed are
     * separate steps.
     */
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

    /**
     * The automatic REVERSAL of an outbound transfer the bank rail
     * rejected: the money goes back to the customer who sent it, with no
     * staff involved.
     */
    public static LedgerTransaction createRailReversal(
            String reference,
            LedgerTransaction original,
            String idempotencyKey,
            String description,
            String providerReference
    ) {
        if (original.type != LedgerTransactionType.OUTBOUND_TRANSFER) {
            throw new IllegalArgumentException("Only an outbound transfer is reversed by the rail");
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

    /** Four eyes, as the database checks too. */
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

    // ============================================================
    // LIFECYCLE
    // ============================================================

    /**
     * INITIATED -> POSTED, but only with a complete, balanced set of
     * entries: at least two, all belonging to this transaction and in its
     * currency, and total debits equal to total credits.
     *
     * The service always builds balanced entries, so a failure here is a
     * bug, not bad input: it throws IllegalStateException (a 500) and the
     * database transaction rolls back without moving any money.
     */
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

    /**
     * POSTED -> REVERSED. Only records the fact; the compensating
     * transaction that undoes the money is posted separately.
     */
    public void markReversed() {

        if (status != LedgerTransactionStatus.POSTED) {
            throw new IllegalStateException("Only a posted transaction can be reversed");
        }

        status = LedgerTransactionStatus.REVERSED;
        reversedAt = Instant.now();
    }

    // ============================================================
    // GETTERS
    // ============================================================

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

    /** The other bank's side of a bank transfer; null for anything else. */
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
