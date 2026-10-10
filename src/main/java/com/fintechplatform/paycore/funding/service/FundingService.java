package com.fintechplatform.paycore.funding.service;

import com.fintechplatform.paycore.common.persistence.ConstraintViolations;
import com.fintechplatform.paycore.funding.config.FundingProperties;
import com.fintechplatform.paycore.funding.exception.FundingDeclinedException;
import com.fintechplatform.paycore.funding.exception.FundingDisabledException;
import com.fintechplatform.paycore.funding.exception.FundingProviderException;
import com.fintechplatform.paycore.funding.provider.FundingCollection;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.funding.provider.FundingResult;
import com.fintechplatform.paycore.funding.provider.FundingStatus;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.dto.response.FundingAllowanceResponse;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.exception.IdempotencyConflictException;
import com.fintechplatform.paycore.ledger.service.FundingTerms;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.payment.config.PaymentProperties;
import com.fintechplatform.paycore.payment.dto.PaymentResponse;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentType;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class FundingService {

    static final String PAYMENT_KEY_CONSTRAINT = "uk_payments_customer_idempotency_key";

    static final String CHARGED_NOT_CREDITED =
            "Your card was charged but the money could not be added to your account. "
                    + "Our operations team will credit or refund it.";

    static final String NOT_CONFIRMED_YET =
            "We have not been able to confirm this payment with the card provider yet. "
                    + "Our operations team is checking it.";

    static final String NEVER_ARRIVED =
            "The payment never reached the card provider; you were not charged";

    public static final String REFUNDED_TO_CARD = "The payment was refunded to your card";

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final ObjectProvider<FundingProvider> providers;
    private final LedgerService ledgerService;
    private final PaymentService paymentService;
    private final FundingProperties properties;
    private final PaymentProperties paymentProperties;

    public FundingService(
            ObjectProvider<FundingProvider> providers,
            LedgerService ledgerService,
            PaymentService paymentService,
            FundingProperties properties,
            PaymentProperties paymentProperties
    ) {
        this.providers = providers;
        this.ledgerService = ledgerService;
        this.paymentService = paymentService;
        this.properties = properties;
        this.paymentProperties = paymentProperties;
    }

    public FundingOutcome fund(UUID customerId, UUID accountId, FundAccountRequest request) {

        FundingProvider provider = providers.getIfAvailable();

        if (provider == null) {
            throw new FundingDisabledException();
        }

        FundingTerms terms = terms(provider);

        Optional<TransactionResponse> previous = ledgerService.checkFunding(customerId, accountId, request, terms);

        if (previous.isPresent()) {
            return FundingOutcome.credited(previous.get());
        }

        Money amount = Money.ofMajor(request.amount(), Currency.of(request.currency()));
        String idempotencyKey = request.idempotencyKey().trim();

        Optional<Payment> existing = paymentService.findByKey(customerId, idempotencyKey);

        if (existing.isPresent()) {
            return replay(existing.get(), accountId, amount);
        }

        Payment payment;

        try {
            payment = paymentService.startFunding(
                    customerId, accountId, amount, idempotencyKey, provider.providerName(),
                    merchantReference(customerId, idempotencyKey), terms.description()
            );
        } catch (DataIntegrityViolationException exception) {

            if (ConstraintViolations.violates(exception, PAYMENT_KEY_CONSTRAINT)) {
                return replay(paymentService.findByKey(customerId, idempotencyKey).orElseThrow(), accountId, amount);
            }

            throw exception;
        }

        FundingResult result;

        try {
            result = collect(provider, customerId, accountId, amount, payment.getExternalReference());
        } catch (FundingProviderException exception) {
            log.warn(
                    "Top-up {} of {} {} to account {}: {} gave no answer ({}); checking its status later",
                    payment.getReference(), amount.toMajor(), amount.currency().code(), accountId,
                    provider.providerName(), exception.getMessage()
            );
            return FundingOutcome.pending(PaymentResponse.from(paymentService.markUnknown(payment.getId())));
        }

        if (!result.confirmed()) {
            log.info(
                    "Top-up {} of {} {} to account {} declined by {} ({}): {}",
                    payment.getReference(), amount.toMajor(), amount.currency().code(), accountId,
                    provider.providerName(), result.providerReference(), result.reason()
            );
            paymentService.failFunding(payment.getId(), declineReason(result.reason()), result.providerReference());
            throw new FundingDeclinedException(result.reason(), result.providerReference());
        }

        return FundingOutcome.credited(credit(payment, terms, result.providerReference()));
    }

    public FundingAllowanceResponse allowance(UUID customerId, UUID accountId) {

        FundingProvider provider = providers.getIfAvailable();

        return ledgerService.getFundingAllowance(
                customerId,
                accountId,
                provider == null ? null : provider.providerName(),
                properties.getMaxTotalPerAccount()
        );
    }

    public Payment settle(Payment payment) {

        FundingStatus status = currentStatus(payment);

        return switch (status == null ? FundingStatus.Outcome.PENDING : status.outcome()) {

            case CONFIRMED -> {
                try {
                    credit(payment, terms(providers.getObject()), status.providerReference());
                } catch (RuntimeException exception) {
                }
                yield paymentService.get(payment.getId());
            }

            case DECLINED -> paymentService.failFunding(
                    payment.getId(), declineReason(status.reason()), status.providerReference()
            );

            case REFUNDED -> paymentService.refunded(payment.getId(), status.refundReference(), REFUNDED_TO_CARD);

            case NOT_FOUND -> payment.getCreatedAt().isBefore(Instant.now().minus(paymentProperties.getNotFoundGrace()))
                    ? paymentService.failFunding(payment.getId(), NEVER_ARRIVED, null)
                    : paymentService.stillUnknown(payment.getId(), NOT_CONFIRMED_YET);

            case PENDING -> paymentService.stillUnknown(payment.getId(), NOT_CONFIRMED_YET);
        };
    }

    public FundingStatus currentStatus(Payment payment) {

        FundingProvider provider = providers.getIfAvailable();

        if (provider == null || !provider.providerName().equals(payment.getProvider())) {
            log.warn("Cannot check top-up {}: {} is not the configured provider",
                    payment.getReference(), payment.getProvider());
            return null;
        }

        try {
            return provider.status(payment.getExternalReference());
        } catch (RuntimeException exception) {
            log.warn("Status check of top-up {} with {} failed: {}",
                    payment.getReference(), provider.providerName(), exception.getMessage());
            return null;
        }
    }

    private TransactionResponse credit(Payment payment, FundingTerms terms, String providerReference) {

        Payment credited;

        try {
            credited = paymentService.completeFunding(payment.getId(), terms, providerReference);
        } catch (RuntimeException exception) {
            log.error(
                    "Payment {} ({}) of {} {} confirmed by {} but NOT credited to account {}: {}. Refund or credit it.",
                    payment.getReference(), providerReference, payment.amount().toMajor(), payment.getCurrency(),
                    payment.getProvider(), payment.getAccountId(), exception.getMessage()
            );
            paymentService.requireReview(payment.getId(), CHARGED_NOT_CREDITED);
            throw exception;
        }

        return ledgerService.getTransaction(credited.getCustomerId(), credited.getLedgerTransactionId());
    }

    private FundingOutcome replay(Payment payment, UUID accountId, Money amount) {

        boolean same =
                payment.getType() == PaymentType.CARD_FUNDING
                        && payment.getAccountId().equals(accountId)
                        && payment.amount().equals(amount);

        if (!same) {
            throw new IdempotencyConflictException();
        }

        return switch (payment.getStatus()) {
            case SUCCEEDED -> FundingOutcome.credited(
                    ledgerService.getTransaction(payment.getCustomerId(), payment.getLedgerTransactionId())
            );
            case FAILED -> throw new FundingDeclinedException(payment.getFailureReason(), payment.getProviderReference());
            default -> FundingOutcome.pending(PaymentResponse.from(payment));
        };
    }

    private static String declineReason(String reason) {
        return reason == null || reason.isBlank() ? "The payment was declined" : reason;
    }

    public FundingTerms terms() {
        return terms(requireProvider());
    }

    public String refund(Payment payment) {

        FundingProvider provider = requireProvider();

        if (!provider.providerName().equals(payment.getProvider())) {
            throw new FundingProviderException(payment.getProvider() + " is not the configured provider", null);
        }

        try {
            return provider.refund(payment.getExternalReference());
        } catch (FundingProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FundingProviderException(provider.providerName() + " failed to refund", exception);
        }
    }

    private FundingProvider requireProvider() {

        FundingProvider provider = providers.getIfAvailable();

        if (provider == null) {
            throw new FundingDisabledException();
        }

        return provider;
    }

    private FundingTerms terms(FundingProvider provider) {
        return new FundingTerms(provider.providerName(), provider.narration(), properties.getMaxTotalPerAccount());
    }

    private FundingResult collect(
            FundingProvider provider,
            UUID customerId,
            UUID accountId,
            Money amount,
            String merchantReference
    ) {

        FundingCollection collection = new FundingCollection(customerId, accountId, amount, merchantReference);

        FundingResult result;

        try {
            result = provider.collect(collection);
        } catch (FundingProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FundingProviderException(provider.providerName() + " failed to answer", exception);
        }

        if (result == null || result.providerReference() == null || result.providerReference().isBlank()) {
            throw new FundingProviderException(provider.providerName() + " returned no payment reference", null);
        }

        return result;
    }

    public static String merchantReference(UUID customerId, String idempotencyKey) {

        try {
            byte[] digest =
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest((customerId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));

            return "PC-" + HexFormat.of().withUpperCase().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
