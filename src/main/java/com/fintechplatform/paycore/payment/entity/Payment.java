package com.fintechplatform.paycore.payment.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.enums.PaymentType;
import com.fintechplatform.paycore.payment.exception.InvalidPaymentTransitionException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @UuidV7
    private UUID id;

    @Column(nullable = false, length = 40, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private PaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 30, updatable = false)
    private String provider;

    @Column(name = "external_reference", nullable = false, length = 100, updatable = false)
    private String externalReference;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(length = 500, updatable = false)
    private String narration;

    @Column(name = "counterparty_name", length = 200, updatable = false)
    private String counterpartyName;

    @Column(name = "counterparty_bank_code", length = 10, updatable = false)
    private String counterpartyBankCode;

    @Column(name = "counterparty_bank", length = 100, updatable = false)
    private String counterpartyBank;

    @Column(name = "counterparty_account_number", length = 20, updatable = false)
    private String counterpartyAccountNumber;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "reversal_transaction_id")
    private UUID reversalTransactionId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "refund_reference", length = 100)
    private String refundReference;

    @Column(name = "status_checks", nullable = false)
    private int statusChecks;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "bill_biller_id", length = 50, updatable = false)
    private String billBillerId;

    @Column(name = "bill_product_code", length = 100, updatable = false)
    private String billProductCode;

    @Column(name = "bill_product_name", length = 200, updatable = false)
    private String billProductName;

    @Column(name = "bill_token", length = 300)
    private String billToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected Payment() {
    }

    public static Payment outboundTransfer(
            String reference,
            UUID customerId,
            UUID accountId,
            Money amount,
            String idempotencyKey,
            String provider,
            String sessionId,
            String narration,
            String bankCode,
            Counterparty beneficiary,
            UUID ledgerTransactionId
    ) {
        Payment payment = newPayment(
                reference, PaymentType.OUTBOUND_TRANSFER, PaymentStatus.CREATED,
                customerId, accountId, amount, idempotencyKey, provider, sessionId, narration
        );
        payment.counterpartyName = beneficiary.name();
        payment.counterpartyBankCode = bankCode;
        payment.counterpartyBank = beneficiary.bank();
        payment.counterpartyAccountNumber = beneficiary.accountNumber();
        payment.ledgerTransactionId = Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId");
        payment.providerReference = sessionId;
        return payment;
    }

    public static Payment billPayment(
            String reference,
            UUID customerId,
            UUID accountId,
            Money amount,
            String idempotencyKey,
            String provider,
            String requestId,
            String narration,
            String billerId,
            String productCode,
            String productName,
            Counterparty biller,
            UUID ledgerTransactionId
    ) {
        Payment payment = newPayment(
                reference, PaymentType.BILL_PAYMENT, PaymentStatus.CREATED,
                customerId, accountId, amount, idempotencyKey, provider, requestId, narration
        );
        payment.billBillerId = Objects.requireNonNull(billerId, "billerId");
        payment.billProductCode = productCode;
        payment.billProductName = productName;
        payment.counterpartyName = biller.name();
        payment.counterpartyBank = biller.bank();
        payment.counterpartyAccountNumber = biller.accountNumber();
        payment.ledgerTransactionId = Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId");
        return payment;
    }

    public static Payment cardFunding(
            String reference,
            UUID customerId,
            UUID accountId,
            Money amount,
            String idempotencyKey,
            String provider,
            String merchantReference,
            String narration
    ) {
        return newPayment(
                reference, PaymentType.CARD_FUNDING, PaymentStatus.PROCESSING,
                customerId, accountId, amount, idempotencyKey, provider, merchantReference, narration
        );
    }

    private static Payment newPayment(
            String reference,
            PaymentType type,
            PaymentStatus status,
            UUID customerId,
            UUID accountId,
            Money amount,
            String idempotencyKey,
            String provider,
            String externalReference,
            String narration
    ) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }

        Payment payment = new Payment();
        payment.reference = Objects.requireNonNull(reference, "reference");
        payment.type = type;
        payment.status = status;
        payment.customerId = Objects.requireNonNull(customerId, "customerId");
        payment.accountId = Objects.requireNonNull(accountId, "accountId");
        payment.amountMinor = amount.amountMinor();
        payment.currency = amount.currency().code();
        payment.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        payment.provider = Objects.requireNonNull(provider, "provider");
        payment.externalReference = Objects.requireNonNull(externalReference, "externalReference");
        payment.narration = narration;
        payment.createdAt = Instant.now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    public void startProcessing() {
        moveTo(PaymentStatus.PROCESSING);
    }

    public void succeed(UUID ledgerTransactionId, String providerReference) {

        moveTo(PaymentStatus.SUCCEEDED);

        if (ledgerTransactionId != null) {
            this.ledgerTransactionId = ledgerTransactionId;
        }

        if (providerReference != null) {
            this.providerReference = providerReference;
        }

        nextCheckAt = null;
        failureReason = null;
    }

    public void fail(String reason, UUID reversalTransactionId, String providerReference) {

        if (isDebitedFirst() && reversalTransactionId == null) {
            throw new IllegalStateException("A failed payment that was debited must have its money returned");
        }

        moveTo(PaymentStatus.FAILED);
        this.failureReason = Objects.requireNonNull(reason, "reason");
        this.reversalTransactionId = reversalTransactionId;
        nextCheckAt = null;

        if (providerReference != null) {
            this.providerReference = providerReference;
        }
    }

    public void refund(String refundReference, String reason) {

        if (type != PaymentType.CARD_FUNDING) {
            throw new IllegalStateException("Only a card top-up is refunded");
        }

        moveTo(PaymentStatus.FAILED);
        this.refundReference = Objects.requireNonNull(refundReference, "refundReference");
        this.failureReason = Objects.requireNonNull(reason, "reason");
        nextCheckAt = null;
    }

    public void recordToken(String token) {
        if (token != null && billToken == null) {
            billToken = token;
            updatedAt = Instant.now();
        }
    }

    public void markUnknown(Instant nextCheckAt) {
        moveTo(PaymentStatus.UNKNOWN);
        this.nextCheckAt = Objects.requireNonNull(nextCheckAt, "nextCheckAt");
    }

    public void recordStatusCheck(Instant nextCheckAt) {

        if (status != PaymentStatus.UNKNOWN && status != PaymentStatus.NEEDS_REVIEW) {
            throw new InvalidPaymentTransitionException(reference, status, status);
        }

        statusChecks++;
        this.nextCheckAt = nextCheckAt;
        updatedAt = Instant.now();
    }

    public void requireReview(String reason) {
        moveTo(PaymentStatus.NEEDS_REVIEW);
        failureReason = Objects.requireNonNull(reason, "reason");
        nextCheckAt = null;
    }

    private void moveTo(PaymentStatus next) {

        if (!status.canMoveTo(next)) {
            throw new InvalidPaymentTransitionException(reference, status, next);
        }

        status = next;
        updatedAt = Instant.now();

        if (next.isFinal()) {
            completedAt = updatedAt;
        }
    }

    public Money amount() {
        return Money.ofMinor(amountMinor, Currency.of(currency));
    }

    public boolean isOutboundTransfer() {
        return type == PaymentType.OUTBOUND_TRANSFER;
    }

    public boolean isBillPayment() {
        return type == PaymentType.BILL_PAYMENT;
    }

    public boolean isDebitedFirst() {
        return type == PaymentType.OUTBOUND_TRANSFER || type == PaymentType.BILL_PAYMENT;
    }

    public UUID getId() {
        return id;
    }

    public String getBillBillerId() {
        return billBillerId;
    }

    public String getBillProductCode() {
        return billProductCode;
    }

    public String getBillProductName() {
        return billProductName;
    }

    public String getBillToken() {
        return billToken;
    }

    public String getReference() {
        return reference;
    }

    public PaymentType getType() {
        return type;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProvider() {
        return provider;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getNarration() {
        return narration;
    }

    public String getCounterpartyName() {
        return counterpartyName;
    }

    public String getCounterpartyBankCode() {
        return counterpartyBankCode;
    }

    public String getCounterpartyBank() {
        return counterpartyBank;
    }

    public String getCounterpartyAccountNumber() {
        return counterpartyAccountNumber;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public UUID getReversalTransactionId() {
        return reversalTransactionId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public int getStatusChecks() {
        return statusChecks;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
