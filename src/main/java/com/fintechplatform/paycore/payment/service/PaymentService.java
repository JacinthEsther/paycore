package com.fintechplatform.paycore.payment.service;

import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.service.FundingTerms;
import com.fintechplatform.paycore.ledger.service.BillDebit;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.OutboundTransfer;
import com.fintechplatform.paycore.payment.config.PaymentProperties;
import com.fintechplatform.paycore.payment.dto.PaymentEventResponse;
import com.fintechplatform.paycore.payment.dto.PaymentResponse;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.exception.InvalidPaymentTransitionException;
import com.fintechplatform.paycore.payment.exception.PaymentNotFoundException;
import com.fintechplatform.paycore.payment.repository.PaymentEventRepository;
import com.fintechplatform.paycore.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    static final String ESCALATED =
            "Our operations team is confirming this payment with the provider.";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final DateTimeFormatter REFERENCE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository eventRepository;
    private final LedgerService ledgerService;
    private final PaymentProperties properties;
    private final SecureRandom random = new SecureRandom();

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentEventRepository eventRepository,
            LedgerService ledgerService,
            PaymentProperties properties
    ) {
        this.paymentRepository = paymentRepository;
        this.eventRepository = eventRepository;
        this.ledgerService = ledgerService;
        this.properties = properties;
    }

    @Transactional
    public Payment initiateOutbound(
            UUID customerId,
            UUID sourceAccountId,
            OutboundTransfer outbound,
            String bankCode
    ) {

        TransactionResponse debit = ledgerService.postOutbound(customerId, sourceAccountId, outbound);

        if (!outbound.sessionId().equals(debit.providerReference())) {
            return paymentRepository
                    .findByCustomerIdAndIdempotencyKey(customerId, outbound.idempotencyKey().trim())
                    .orElseThrow(() -> new IllegalStateException(
                            "Outbound transfer " + debit.reference() + " has no payment"
                    ));
        }

        Payment payment =
                Payment.outboundTransfer(
                        generateReference(),
                        customerId,
                        sourceAccountId,
                        Money.ofMajor(outbound.amount(), Currency.of(outbound.currency())),
                        outbound.idempotencyKey().trim(),
                        outbound.provider(),
                        outbound.sessionId(),
                        debit.description(),
                        bankCode,
                        outbound.beneficiary(),
                        debit.id()
                );

        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment initiateBill(
            UUID customerId,
            UUID sourceAccountId,
            BillDebit bill,
            String billerId,
            String productCode,
            String productName
    ) {

        TransactionResponse debit = ledgerService.postBillPayment(customerId, sourceAccountId, bill);

        if (!bill.requestId().equals(debit.providerReference())) {
            return paymentRepository
                    .findByCustomerIdAndIdempotencyKey(customerId, bill.idempotencyKey().trim())
                    .orElseThrow(() -> new IllegalStateException(
                            "Bill payment " + debit.reference() + " has no payment"
                    ));
        }

        Payment payment =
                Payment.billPayment(
                        generateReference(),
                        customerId,
                        sourceAccountId,
                        Money.ofMajor(bill.amount(), Currency.of(bill.currency())),
                        bill.idempotencyKey().trim(),
                        bill.provider(),
                        bill.requestId(),
                        debit.description(),
                        billerId,
                        productCode,
                        productName,
                        bill.biller(),
                        debit.id()
                );

        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional
    public Payment failOutbound(UUID paymentId, String reason) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() == PaymentStatus.FAILED) {
            return payment;
        }

        requireCanMove(payment, PaymentStatus.FAILED);

        TransactionResponse reversal =
                ledgerService.reverseRejectedOutbound(payment.getLedgerTransactionId(), reason);

        payment.fail(reason, reversal.id(), null);

        return payment;
    }

    @Transactional
    public Optional<Payment> failOutboundIfUnsent(UUID paymentId, Instant staleBefore, String reason) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() != PaymentStatus.CREATED || !payment.getUpdatedAt().isBefore(staleBefore)) {
            return Optional.empty();
        }

        log.warn("Outbound transfer {} was debited but never sent; returning the money", payment.getReference());

        TransactionResponse reversal =
                ledgerService.reverseRejectedOutbound(payment.getLedgerTransactionId(), reason);

        payment.fail(reason, reversal.id(), null);

        return Optional.of(payment);
    }

    @Transactional
    public Payment startFunding(
            UUID customerId,
            UUID accountId,
            Money amount,
            String idempotencyKey,
            String provider,
            String merchantReference,
            String narration
    ) {

        return paymentRepository.saveAndFlush(
                Payment.cardFunding(
                        generateReference(), customerId, accountId, amount,
                        idempotencyKey, provider, merchantReference, narration
                )
        );
    }

    @Transactional
    public Payment completeFunding(UUID paymentId, FundingTerms terms, String providerReference) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            return payment;
        }

        requireCanMove(payment, PaymentStatus.SUCCEEDED);

        TransactionResponse credit =
                ledgerService.postFunding(
                        payment.getCustomerId(),
                        payment.getAccountId(),
                        new FundAccountRequest(payment.amount().toMajor(), payment.getCurrency(), payment.getIdempotencyKey()),
                        terms,
                        providerReference
                );

        if (!providerReference.equals(credit.providerReference())) {
            throw new IllegalStateException(
                    "Payment " + payment.getReference() + " (" + providerReference + ") matched deposit "
                            + credit.reference() + " of provider payment " + credit.providerReference()
            );
        }

        payment.succeed(credit.id(), providerReference);

        return payment;
    }

    @Transactional
    public Payment failFunding(UUID paymentId, String reason, String providerReference) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() == PaymentStatus.FAILED) {
            return payment;
        }

        payment.fail(reason, null, providerReference);

        return payment;
    }

    @Transactional
    public Payment markProcessing(UUID paymentId) {

        Payment payment = lock(paymentId);
        payment.startProcessing();
        return payment;
    }

    @Transactional
    public Payment succeed(UUID paymentId) {
        return succeed(paymentId, null);
    }

    @Transactional
    public Payment succeed(UUID paymentId, String billToken) {

        Payment payment = lock(paymentId);

        payment.recordToken(billToken);

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            payment.succeed(null, null);
        }

        return payment;
    }

    @Transactional
    public Payment markUnknown(UUID paymentId) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            payment.markUnknown(Instant.now().plus(properties.getFirstCheckAfter()));
        }

        return payment;
    }

    @Transactional
    public Optional<Payment> markUnknownIfStale(UUID paymentId, Instant staleBefore) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() != PaymentStatus.PROCESSING || !payment.getUpdatedAt().isBefore(staleBefore)) {
            return Optional.empty();
        }

        log.warn("Payment {} was left PROCESSING since {}; checking with {}",
                payment.getReference(), payment.getUpdatedAt(), payment.getProvider());

        payment.markUnknown(Instant.now());

        return Optional.of(payment);
    }

    @Transactional
    public Optional<Payment> claimStatusCheck(UUID paymentId, boolean manual) {

        Payment payment = lock(paymentId);
        Instant now = Instant.now();

        PaymentStatus status = payment.getStatus();

        if (status.isFinal()) {
            return Optional.empty();
        }

        if (!manual && (status != PaymentStatus.UNKNOWN || payment.getNextCheckAt().isAfter(now))) {
            return Optional.empty();
        }

        if (status == PaymentStatus.CREATED || status == PaymentStatus.PROCESSING) {
            throw new InvalidPaymentTransitionException(payment.getReference(), status, PaymentStatus.UNKNOWN);
        }

        payment.recordStatusCheck(
                status == PaymentStatus.UNKNOWN
                        ? now.plus(properties.backoffAfter(payment.getStatusChecks()))
                        : null
        );

        return Optional.of(payment);
    }

    @Transactional
    public Payment stillUnknown(UUID paymentId, String reviewReason) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() == PaymentStatus.UNKNOWN && payment.getStatusChecks() >= properties.getMaxChecks()) {
            log.error("Payment {} is still unknown after {} status checks; operations must resolve it",
                    payment.getReference(), payment.getStatusChecks());
            payment.requireReview(reviewReason);
        }

        return payment;
    }

    @Transactional
    public Payment lockForResolution(UUID paymentId) {
        return lock(paymentId);
    }

    @Transactional
    public Payment failReversed(UUID paymentId, String reason, UUID reversalTransactionId) {

        Payment payment = lock(paymentId);
        payment.fail(reason, reversalTransactionId, null);
        return payment;
    }

    @Transactional
    public Payment refunded(UUID paymentId, String refundReference, String reason) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() != PaymentStatus.FAILED) {
            payment.refund(refundReference, reason);
        }

        return payment;
    }

    @Transactional
    public Payment escalate(UUID paymentId) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            throw new InvalidPaymentTransitionException(payment.getReference(), payment.getStatus(), PaymentStatus.NEEDS_REVIEW);
        }

        log.info("Payment {} escalated to operations", payment.getReference());

        payment.requireReview(ESCALATED);

        return payment;
    }

    @Transactional
    public Payment requireReview(UUID paymentId, String reason) {

        Payment payment = lock(paymentId);

        if (payment.getStatus() != PaymentStatus.NEEDS_REVIEW) {
            payment.requireReview(reason);
        }

        return payment;
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByKey(UUID customerId, String idempotencyKey) {
        return paymentRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey.trim());
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getOwn(UUID customerId, UUID paymentId) {

        return paymentRepository
                .findByIdAndCustomerId(paymentId, customerId)
                .map(PaymentResponse::from)
                .orElseThrow(PaymentNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getOwnByReference(UUID customerId, String reference) {

        return paymentRepository
                .findByReferenceAndCustomerId(reference, customerId)
                .map(PaymentResponse::from)
                .orElseThrow(PaymentNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByReference(String reference) {

        return paymentRepository
                .findByReference(reference)
                .map(PaymentResponse::from)
                .orElseThrow(PaymentNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> list(PaymentStatus status, Pageable pageable) {

        Page<Payment> page =
                status == null ? paymentRepository.findAll(pageable) : paymentRepository.findByStatus(status, pageable);

        return page.map(PaymentResponse::from);
    }

    @Transactional(readOnly = true)
    public List<PaymentEventResponse> events(UUID paymentId) {

        if (!paymentRepository.existsById(paymentId)) {
            throw new PaymentNotFoundException();
        }

        return eventRepository
                .findByPaymentIdOrderByCreatedAtAsc(paymentId)
                .stream()
                .map(PaymentEventResponse::from)
                .toList();
    }

    private Payment lock(UUID paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId).orElseThrow(PaymentNotFoundException::new);
    }

    private static void requireCanMove(Payment payment, PaymentStatus next) {

        if (!payment.getStatus().canMoveTo(next)) {
            throw new InvalidPaymentTransitionException(payment.getReference(), payment.getStatus(), next);
        }
    }

    private String generateReference() {

        byte[] suffix = new byte[8];
        random.nextBytes(suffix);

        return "PAY-"
                + REFERENCE_DATE.format(Instant.now())
                + "-"
                + HexFormat.of().withUpperCase().formatHex(suffix);
    }
}
