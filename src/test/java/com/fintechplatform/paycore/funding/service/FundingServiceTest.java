package com.fintechplatform.paycore.funding.service;

import com.fintechplatform.paycore.account.exception.InvalidAccountStateException;
import com.fintechplatform.paycore.funding.config.FundingProperties;
import com.fintechplatform.paycore.funding.exception.FundingDeclinedException;
import com.fintechplatform.paycore.funding.exception.FundingDisabledException;
import com.fintechplatform.paycore.funding.provider.FundingCollection;
import com.fintechplatform.paycore.funding.provider.FundingProvider;
import com.fintechplatform.paycore.funding.provider.FundingResult;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionStatus;
import com.fintechplatform.paycore.ledger.enums.LedgerTransactionType;
import com.fintechplatform.paycore.ledger.exception.FundingLimitExceededException;
import com.fintechplatform.paycore.ledger.exception.IdempotencyConflictException;
import com.fintechplatform.paycore.ledger.service.FundingTerms;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.payment.config.PaymentProperties;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.service.PaymentService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FundingServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Money AMOUNT = Money.ofMajor(new BigDecimal("5000"), Currency.of("NGN"));

    private final FundingProvider provider = mock(FundingProvider.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FundingProvider> providers = mock(ObjectProvider.class);

    private final FundAccountRequest request =
            new FundAccountRequest(new BigDecimal("5000"), "NGN", "topup-key-1");

    private FundingService service;

    @BeforeEach
    void setUp() {
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.providerName()).thenReturn("TESTPAY");
        when(provider.narration()).thenReturn("Top-up");

        when(ledgerService.checkFunding(eq(CUSTOMER), eq(ACCOUNT), eq(request), any())).thenReturn(Optional.empty());
        when(paymentService.findByKey(CUSTOMER, "topup-key-1")).thenReturn(Optional.empty());
        when(paymentService.startFunding(any(), any(), any(), any(), any(), any(), any())).thenReturn(payment());

        service = new FundingService(
                providers, ledgerService, paymentService, new FundingProperties(), new PaymentProperties()
        );
    }

    @Test
    void shouldRecordThePaymentChargeTheProviderThenCreditTheConfirmedPayment() {

        when(provider.collect(any())).thenReturn(FundingResult.confirmed("TP-1"));
        Payment credited = payment();
        when(paymentService.completeFunding(any(), any(), eq("TP-1"))).thenReturn(credited);
        TransactionResponse posted = funding("TP-1");
        when(ledgerService.getTransaction(any(), any())).thenReturn(posted);

        FundingOutcome outcome = service.fund(CUSTOMER, ACCOUNT, request);

        assertThat(outcome.isCredited()).isTrue();
        assertThat(outcome.transaction()).isSameAs(posted);

        String merchantReference = FundingService.merchantReference(CUSTOMER, "topup-key-1");
        verify(paymentService).startFunding(
                CUSTOMER, ACCOUNT, AMOUNT, "topup-key-1", "TESTPAY", merchantReference, "Top-up"
        );

        ArgumentCaptor<FundingCollection> collection = ArgumentCaptor.forClass(FundingCollection.class);
        verify(provider).collect(collection.capture());
        assertThat(collection.getValue().amount()).isEqualTo(AMOUNT);
        assertThat(collection.getValue().merchantReference()).isEqualTo(merchantReference);

        ArgumentCaptor<FundingTerms> terms = ArgumentCaptor.forClass(FundingTerms.class);
        verify(paymentService).completeFunding(any(), terms.capture(), eq("TP-1"));
        assertThat(terms.getValue().provider()).isEqualTo("TESTPAY");
        assertThat(terms.getValue().description()).isEqualTo("Top-up");
        assertThat(terms.getValue().limitPerAccount()).isEqualByComparingTo("500000");
    }

    @Test
    void aRetryOfAPostedTopUpShouldNotChargeAgain() {

        TransactionResponse previous = funding("TP-1");
        when(ledgerService.checkFunding(any(), any(), any(), any())).thenReturn(Optional.of(previous));

        assertThat(service.fund(CUSTOMER, ACCOUNT, request).transaction()).isSameAs(previous);

        verify(provider, never()).collect(any());
        verify(paymentService, never()).startFunding(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRetryWhileTheOutcomeIsUnknownShouldNotChargeAgain() {

        Payment unknown = payment();
        unknown.markUnknown(Instant.now());
        when(paymentService.findByKey(CUSTOMER, "topup-key-1")).thenReturn(Optional.of(unknown));

        FundingOutcome outcome = service.fund(CUSTOMER, ACCOUNT, request);

        assertThat(outcome.isCredited()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(provider, never()).collect(any());
    }

    @Test
    void aRetryOfADeclinedTopUpShouldBeDeclinedAgainWithoutCharging() {

        Payment declined = payment();
        declined.fail("Card declined", null, "TP-9");
        when(paymentService.findByKey(CUSTOMER, "topup-key-1")).thenReturn(Optional.of(declined));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingDeclinedException.class)
                .hasMessage("Card declined");

        verify(provider, never()).collect(any());
    }

    @Test
    void theSameKeyForADifferentTopUpShouldBeRefused() {

        when(paymentService.findByKey(CUSTOMER, "topup-key-1")).thenReturn(Optional.of(payment()));

        FundAccountRequest different = new FundAccountRequest(new BigDecimal("7000"), "NGN", "topup-key-1");
        when(ledgerService.checkFunding(any(), any(), eq(different), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, different))
                .isInstanceOf(IdempotencyConflictException.class);

        verify(provider, never()).collect(any());
    }

    @Test
    void aConcurrentDuplicateShouldGetTheFirstPaymentAndNotCharge() {

        when(paymentService.startFunding(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate", new ConstraintViolationException(
                        "duplicate", new SQLException(), FundingService.PAYMENT_KEY_CONSTRAINT
                )));
        when(paymentService.findByKey(CUSTOMER, "topup-key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(payment()));

        FundingOutcome outcome = service.fund(CUSTOMER, ACCOUNT, request);

        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.PROCESSING);
        verify(provider, never()).collect(any());
    }

    @Test
    void aRefusedCheckShouldNotChargeTheCustomer() {

        when(ledgerService.checkFunding(any(), any(), any(), any()))
                .thenThrow(new FundingLimitExceededException(
                        Money.ofMinor(100, Currency.of("NGN")), Money.ofMinor(0, Currency.of("NGN"))
                ));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingLimitExceededException.class);

        verify(provider, never()).collect(any());
        verify(paymentService, never()).startFunding(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDeclinedPaymentShouldFailAndCreditNothing() {

        when(provider.collect(any())).thenReturn(FundingResult.declined("TP-2", "Card declined"));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingDeclinedException.class)
                .hasMessage("Card declined")
                .extracting(e -> ((FundingDeclinedException) e).getProviderReference())
                .isEqualTo("TP-2");

        verify(paymentService).failFunding(any(), eq("Card declined"), eq("TP-2"));
        verify(paymentService, never()).completeFunding(any(), any(), anyString());
    }

    @Test
    void aProviderFailureShouldLeaveThePaymentUnknownNotFailed() {

        when(provider.collect(any())).thenThrow(new IllegalStateException("connection reset"));
        Payment unknown = payment();
        unknown.markUnknown(Instant.now());
        when(paymentService.markUnknown(any())).thenReturn(unknown);

        FundingOutcome outcome = service.fund(CUSTOMER, ACCOUNT, request);

        assertThat(outcome.isCredited()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(paymentService, never()).failFunding(any(), any(), any());
        verify(paymentService, never()).completeFunding(any(), any(), any());
    }

    @Test
    void aConfirmationWithoutAReferenceShouldNotBeCredited() {

        when(provider.collect(any())).thenReturn(FundingResult.confirmed(null));
        Payment unknown = payment();
        unknown.markUnknown(Instant.now());
        when(paymentService.markUnknown(any())).thenReturn(unknown);

        assertThat(service.fund(CUSTOMER, ACCOUNT, request).isCredited()).isFalse();

        verify(paymentService, never()).completeFunding(any(), any(), any());
    }

    @Test
    void aConfirmedChargeThatCannotBeCreditedShouldGoToReview() {

        when(provider.collect(any())).thenReturn(FundingResult.confirmed("TP-3"));
        when(paymentService.completeFunding(any(), any(), eq("TP-3")))
                .thenThrow(new InvalidAccountStateException("Your account is frozen and cannot receive money"));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(InvalidAccountStateException.class);

        verify(paymentService).requireReview(any(), eq(FundingService.CHARGED_NOT_CREDITED));
    }

    @Test
    void withoutAProviderTopUpsShouldBeDisabled() {

        when(providers.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingDisabledException.class);

        verifyNoInteractions(ledgerService);
        verifyNoInteractions(paymentService);
    }

    @Test
    void allowanceShouldNameTheProviderOrNone() {

        service.allowance(CUSTOMER, ACCOUNT);
        verify(ledgerService).getFundingAllowance(eq(CUSTOMER), eq(ACCOUNT), eq("TESTPAY"), any());

        when(providers.getIfAvailable()).thenReturn(null);

        service.allowance(CUSTOMER, ACCOUNT);
        verify(ledgerService).getFundingAllowance(eq(CUSTOMER), eq(ACCOUNT), eq(null), any());
    }

    @Test
    void merchantReferenceShouldBeStablePerCustomerKeyAndHideTheKey() {

        String reference = FundingService.merchantReference(CUSTOMER, "topup-key-1");

        assertThat(reference).matches("PC-[0-9A-F]{32}").doesNotContain("topup-key-1");
        assertThat(FundingService.merchantReference(CUSTOMER, "topup-key-1")).isEqualTo(reference);
        assertThat(FundingService.merchantReference(CUSTOMER, "topup-key-2")).isNotEqualTo(reference);
        assertThat(FundingService.merchantReference(UUID.randomUUID(), "topup-key-1")).isNotEqualTo(reference);
    }

    private static Payment payment() {

        return Payment.cardFunding(
                "PAY-TEST", CUSTOMER, ACCOUNT, AMOUNT, "topup-key-1", "TESTPAY",
                FundingService.merchantReference(CUSTOMER, "topup-key-1"), "Top-up"
        );
    }

    private static TransactionResponse funding(String providerReference) {

        return new TransactionResponse(
                UUID.randomUUID(), "TXN-1", LedgerTransactionType.DEPOSIT, LedgerTransactionStatus.POSTED,
                new BigDecimal("5000.00"), "NGN", "Top-up", null, "TESTPAY", providerReference,
                Instant.now(), Instant.now(), null, null, List.of()
        );
    }
}
