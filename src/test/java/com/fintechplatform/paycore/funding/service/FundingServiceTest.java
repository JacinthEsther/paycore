package com.fintechplatform.paycore.funding.service;

import com.fintechplatform.paycore.funding.config.FundingProperties;
import com.fintechplatform.paycore.funding.exception.FundingDeclinedException;
import com.fintechplatform.paycore.funding.exception.FundingDisabledException;
import com.fintechplatform.paycore.funding.exception.FundingProviderException;
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
import com.fintechplatform.paycore.ledger.service.FundingTerms;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
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

/**
 * The order of the steps and what happens when the provider says no or
 * cannot answer. The ledger side runs against PostgreSQL in
 * FundingIntegrationTest.
 */
class FundingServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final FundingProvider provider = mock(FundingProvider.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
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

        service = new FundingService(providers, ledgerService, new FundingProperties());
    }

    @Test
    void shouldChargeTheProviderThenCreditTheConfirmedPayment() {

        when(ledgerService.checkFunding(eq(CUSTOMER), eq(ACCOUNT), eq(request), any())).thenReturn(Optional.empty());
        when(provider.collect(any())).thenReturn(FundingResult.confirmed("TP-1"));
        TransactionResponse posted = funding("TP-1");
        when(ledgerService.postFunding(eq(CUSTOMER), eq(ACCOUNT), eq(request), any(), eq("TP-1"))).thenReturn(posted);

        assertThat(service.fund(CUSTOMER, ACCOUNT, request)).isSameAs(posted);

        ArgumentCaptor<FundingCollection> collection = ArgumentCaptor.forClass(FundingCollection.class);
        verify(provider).collect(collection.capture());
        assertThat(collection.getValue().amount()).isEqualTo(Money.ofMajor(new BigDecimal("5000"), Currency.of("NGN")));
        assertThat(collection.getValue().merchantReference())
                .isEqualTo(FundingService.merchantReference(CUSTOMER, "topup-key-1"));

        ArgumentCaptor<FundingTerms> terms = ArgumentCaptor.forClass(FundingTerms.class);
        verify(ledgerService).postFunding(eq(CUSTOMER), eq(ACCOUNT), eq(request), terms.capture(), eq("TP-1"));
        assertThat(terms.getValue().provider()).isEqualTo("TESTPAY");
        assertThat(terms.getValue().description()).isEqualTo("Top-up");
        assertThat(terms.getValue().limitPerAccount()).isEqualByComparingTo("500000");
    }

    @Test
    void aRetryOfAPostedTopUpShouldNotChargeAgain() {

        TransactionResponse previous = funding("TP-1");
        when(ledgerService.checkFunding(any(), any(), any(), any())).thenReturn(Optional.of(previous));

        assertThat(service.fund(CUSTOMER, ACCOUNT, request)).isSameAs(previous);

        verify(provider, never()).collect(any());
        verify(ledgerService, never()).postFunding(any(), any(), any(), any(), anyString());
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
    }

    @Test
    void aDeclinedPaymentShouldCreditNothing() {

        when(ledgerService.checkFunding(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(provider.collect(any())).thenReturn(FundingResult.declined("TP-2", "Card declined"));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingDeclinedException.class)
                .hasMessage("Card declined")
                .extracting(e -> ((FundingDeclinedException) e).getProviderReference())
                .isEqualTo("TP-2");

        verify(ledgerService, never()).postFunding(any(), any(), any(), any(), anyString());
    }

    @Test
    void aProviderFailureShouldBecomeAProviderExceptionAndCreditNothing() {

        when(ledgerService.checkFunding(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(provider.collect(any())).thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingProviderException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(ledgerService, never()).postFunding(any(), any(), any(), any(), anyString());
    }

    @Test
    void aConfirmationWithoutAReferenceShouldNotBeCredited() {

        when(ledgerService.checkFunding(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(provider.collect(any())).thenReturn(FundingResult.confirmed(null));

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingProviderException.class);

        verify(ledgerService, never()).postFunding(any(), any(), any(), any(), any());
    }

    @Test
    void withoutAProviderTopUpsShouldBeDisabled() {

        when(providers.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.fund(CUSTOMER, ACCOUNT, request))
                .isInstanceOf(FundingDisabledException.class);

        verifyNoInteractions(ledgerService);
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

    private static TransactionResponse funding(String providerReference) {

        return new TransactionResponse(
                UUID.randomUUID(), "TXN-1", LedgerTransactionType.DEPOSIT, LedgerTransactionStatus.POSTED,
                new BigDecimal("5000.00"), "NGN", "Top-up", null, "TESTPAY", providerReference,
                Instant.now(), Instant.now(), null, null, List.of()
        );
    }
}
